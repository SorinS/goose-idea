"""LLDB formatters for programs built by the Goose compiler.

Goose compiles to C, so LLDB sees the generated C types. This module
recognizes them by name and shape and shows them as Goose values:

  sl_T                  T[:] slice           {T *data; int64_t len;}
  aN_T                  T[N] fixed array     {T e[N];}
  lN_T                  T[..N] limited array {<uint> len; T e[N];}
  gs_rhdr               T[>..], T[>..<]      {uint8_t *base; int64_t len;}
  gs_rref, gs_pref      a reference to one   {gs_rhdr *hdr; gs_stack *stk; ...}
  r_T, o_T              T& and T?            typedefs of T *
  Name_g, Name_g_Args   struct
  Name_g {tag; u}       enum (fixed mode)    {uint8_t tag; union {X_p v_X_g; ...} u;}
  Name_g {tag}          enum, no payloads    {uint8_t tag;}
  Name_g_Variant_p      a variant's payload, the type of a Shape.Circle value

Load it with `command script import /path/to/goose_lldb.py`.

A compiler with the debug map (goose -g writes <stem>.debug.json
beside the generated <stem>.c) says what the C cannot: each function's and
local's Goose name and type, the line range a local is in scope over, and
the fields and variants of the types they name. Where the map is found
(the compile unit's .c with .debug.json in place of .c, no older than the
.c), values are decoded from memory by their Goose type: the elements of a
resizable, a variable-size value behind a bare uint8_t *, an enum's
variant, a bool, a reference against an optional, a relative reference.
Set GOOSE_LLDB_MAP=0 to ignore the map.

Without a map, tag names come from the generated C file named by the
compile unit, when it still exists: the C declares them in an anonymous
enum that dsymutil drops from the debug info. Set GOOSE_LLDB_CSOURCE=0 to
turn that off as well and see only what DWARF gives.

Nothing here may raise into LLDB: every entry point catches its errors and
falls back to showing less.
"""

import json
import os
import re
import struct

import lldb

CATEGORY = "goose"

# Caps, so that a garbage header (an uninitialized local) cannot make LLDB
# read gigabytes or list millions of children.
MAX_CHILDREN = 256
MAX_TEXT = 512
MAX_INLINE = 16
MAX_SUMMARY = 400
MAX_LEN = 1 << 40
# References followed inside one summary; deeper ones show as addresses.
MAX_DEPTH = 2

_FIXED_RE = re.compile(r"^a[0-9]+_")
_LIMITED_RE = re.compile(r"^l[0-9]+_")
# A Goose field, local or function name: x_g, it_g_2, bump_g_1, x_g2.
_NAME_RE = re.compile(r"^(.+?)_g(_?[0-9]+)?$")


def _env_on(name):
    return os.environ.get(name, "1") not in ("0", "off", "no", "false")


def _csource_enabled():
    return _env_on("GOOSE_LLDB_CSOURCE")


# --------------------------------------------------------------------------
# Names


def goose_name(cname):
    """x_g -> x, it_g_2 -> it, bump_g_1 -> bump; anything else unchanged."""
    m = _NAME_RE.match(cname or "")
    return m.group(1) if m else cname


def goose_type_name(cname):
    """Shape_g -> Shape, Pair_g_i64 -> Pair<i64>. Best effort: type arguments
    are shown as they are mangled."""
    if not cname:
        return cname
    if cname.endswith("_g"):
        return cname[:-2]
    i = cname.find("_g_")
    if i > 0:
        return "%s<%s>" % (cname[:i], cname[i + 3:])
    return cname


# --------------------------------------------------------------------------
# Classification


def _fields(t):
    t = t.GetCanonicalType()
    if t.GetTypeClass() not in (lldb.eTypeClassStruct, lldb.eTypeClassClass):
        return None
    return [t.GetFieldAtIndex(i) for i in range(t.GetNumberOfFields())]


def type_name(t):
    """The name a C type is written with, typedef kept, qualifiers and the
    struct keyword dropped. (SBType.GetUnqualifiedType strips typedefs too.)"""
    n = t.GetName() or ""
    for q in ("const ", "volatile ", "struct "):
        while n.startswith(q):
            n = n[len(q):]
    return n


def _is_goose_name(name):
    return name.endswith("_g") or "_g_" in name


def classify(t):
    """The kind of Goose value a C type holds, or None."""
    try:
        name = type_name(t)
        canon = t.GetCanonicalType()
        if t.IsTypedefType() and canon.IsPointerType():
            if name.startswith("r_"):
                return "ref"
            if name.startswith("o_"):
                return "opt"
            return None
        if name == "uint8_t *":
            return "bytes"
        fs = _fields(t)
        if fs is None:
            return None
        names = [f.GetName() for f in fs]
        if name in ("gs_rhdr",) and names == ["base", "len"]:
            return "rhdr"
        if name in ("gs_rref", "gs_pref") and names[:2] == ["hdr", "stk"]:
            return "rref"
        if name.startswith("sl_") and names == ["data", "len"]:
            return "slice"
        if _FIXED_RE.match(name) and names == ["e"]:
            return "fixed"
        if _LIMITED_RE.match(name) and names == ["len", "e"]:
            return "limited"
        if not _is_goose_name(name) and not name.endswith("_p"):
            return None
        if names == ["tag", "u"] and fs[1].GetType().GetCanonicalType().GetTypeClass() == lldb.eTypeClassUnion:
            return "adt"
        if names == ["tag"]:
            return "cenum"
        if name.endswith("_p") and "_g_" in name:
            return "payload"
        if _is_goose_name(name):
            return "struct"
    except Exception:
        pass
    return None


def recognize(sbtype, internal_dict):
    return classify(sbtype) is not None


# --------------------------------------------------------------------------
# Tag names from the generated C


_enum_cache = {}
_ENUM_RE = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*)_k\s*=\s*([0-9]+)\s*,")


def _cu_paths(target):
    paths = []
    try:
        for m in target.module_iter():
            for i in range(m.GetNumCompileUnits()):
                fs = m.GetCompileUnitAtIndex(i).GetFileSpec()
                p = fs.fullpath
                if p and p.endswith(".c"):
                    paths.append(p)
    except Exception:
        pass
    return paths


def _tag_constants(target):
    """{"Shape_g_Circle_g": 0, ...} from the generated C files, or {}."""
    if not _csource_enabled():
        return {}
    paths = tuple(_cu_paths(target))
    if paths in _enum_cache:
        return _enum_cache[paths]
    consts = {}
    for p in paths:
        try:
            if os.path.getsize(p) > 64 << 20:
                continue
            with open(p, "r", errors="replace") as f:
                text = f.read()
        except Exception:
            continue
        for block in re.finditer(r"enum\s*\{([^}]*)\}\s*;", text):
            for m in _ENUM_RE.finditer(block.group(1)):
                consts[m.group(1)] = int(m.group(2))
    _enum_cache[paths] = consts
    return consts


def tag_name(valobj, enum_cname, tag):
    """The variant name for a tag value, or None when it cannot be known:
    from the debug map, else from the generated C."""
    try:
        target = valobj.GetTarget()
        m = get_map(target)
        if m is not None:
            td = m.types_by_c.get(enum_cname)
            if td is not None:
                for v in td.get("variants", []):
                    if v.get("tag") == tag:
                        return v.get("name")
        prefix = enum_cname + "_"
        for cname, v in _tag_constants(target).items():
            if v == tag and cname.startswith(prefix) and cname.endswith("_g"):
                return cname[len(prefix):-2]
    except Exception:
        pass
    return None


def payload_owner(valobj, payload_cname):
    """For Shape_g_Circle_p, ("Shape_g", "Circle"): the enum whose union has a
    member v_Circle_g of this type. Found in DWARF, so it needs the enum to be
    used somewhere in the program."""
    try:
        target = valobj.GetTarget()
        stem = payload_cname[:-2]
        i = stem.find("_g_")
        if i <= 0:
            return None, None
        # Shape_g_Circle splits after Shape_g, Opt_g_i64_Some after
        # Opt_g_i64: try each underscore past the _g.
        j = stem.find("_", i + 2)
        while j > 0:
            ename, variant = stem[:j], stem[j + 1:]
            et = target.FindFirstType(ename)
            if et.IsValid():
                u = _member(et, "u")
                if u is not None and _member(u, "v_%s_g" % variant) is not None:
                    return ename, variant
            j = stem.find("_", j + 1)
        # No enum using it was found: split at the first _g_.
        if i > 0:
            return stem[:i + 2], stem[i + 3:]
    except Exception:
        pass
    return None, None


def _member(t, name):
    t = t.GetCanonicalType()
    for i in range(t.GetNumberOfFields()):
        f = t.GetFieldAtIndex(i)
        if f.GetName() == name:
            return f.GetType()
    return None


# --------------------------------------------------------------------------
# Values


def _clip(s, n=MAX_SUMMARY):
    return s if len(s) <= n else s[:n - 3] + "..."


def _int_of(v):
    """A scalar child's value as text: integers in decimal (u8 is a number in
    Goose, not a character), floats as LLDB prints them."""
    t = v.GetType().GetCanonicalType()
    basic = t.GetBasicType()
    if basic in (lldb.eBasicTypeFloat, lldb.eBasicTypeDouble, lldb.eBasicTypeLongDouble):
        return v.GetValue() or "?"
    if basic == lldb.eBasicTypeBool:
        return "true" if v.GetValueAsUnsigned() else "false"
    if t.GetTypeFlags() & lldb.eTypeIsInteger:
        if t.GetTypeFlags() & lldb.eTypeIsSigned:
            return str(v.GetValueAsSigned())
        return str(v.GetValueAsUnsigned())
    return None


def value_text(v):
    """A child's value as one line of text."""
    try:
        if not v.IsValid() or v.GetError().Fail():
            return "?"
        s = _int_of(v)
        if s is not None:
            return s
        s = v.GetSummary()
        if s:
            return s
        t = v.GetType().GetCanonicalType()
        if t.IsPointerType():
            # A reference is transparent in Goose: show what a field such
            # as `line: i64&` points at, when it is a readable scalar.
            a = v.GetValueAsUnsigned()
            if a == 0:
                return "null"
            d = v.Dereference()
            if d.IsValid() and d.GetError().Success():
                s = _int_of(d)
                if s is not None:
                    return s
            return "0x%x" % a
        s = v.GetValue()
        if s:
            return s
        n = v.GetNumChildren()
        parts = [value_text(v.GetChildAtIndex(i)) for i in range(min(n, MAX_INLINE))]
        if n > MAX_INLINE:
            parts.append("...")
        return "{" + ", ".join(parts) + "}"
    except Exception:
        return "?"


def _is_u8(t):
    t = t.GetCanonicalType()
    return t.GetByteSize() == 1 and t.GetBasicType() in (
        lldb.eBasicTypeUnsignedChar, lldb.eBasicTypeChar, lldb.eBasicTypeSignedChar)


def quote_bytes(data, total):
    out = ['"']
    for b in data:
        if b == 0x22:
            out.append('\\"')
        elif b == 0x5C:
            out.append("\\\\")
        elif b == 0x0A:
            out.append("\\n")
        elif b == 0x09:
            out.append("\\t")
        elif b == 0x0D:
            out.append("\\r")
        elif 0x20 <= b < 0x7F:
            out.append(chr(b))
        else:
            out.append("\\x%02x" % b)
    out.append('"')
    s = "".join(out)
    if total > len(data):
        s += "... (%d bytes)" % total
    return s


def read_text(process, addr, n):
    if n == 0:
        return '""'
    if addr == 0:
        return "<null data, len=%d>" % n
    k = min(n, MAX_TEXT)
    err = lldb.SBError()
    data = process.ReadMemory(addr, k, err)
    if err.Fail() or data is None:
        return "<unreadable at 0x%x, len=%d>" % (addr, n)
    return quote_bytes(bytearray(data), n)


def _list_text(children, total):
    parts = [value_text(c) for c in children[:MAX_INLINE]]
    if total > MAX_INLINE:
        parts.append("... (%d elements)" % total)
    return "[" + ", ".join(parts) + "]"


# --------------------------------------------------------------------------
# The debug map


class GooseMap:
    """One program's <stem>.debug.json files, indexed: functions by C name,
    types by Goose name and by C name."""

    def __init__(self, docs):
        self.funcs = {}
        self.types = {}
        self.types_by_c = {}
        self.globals = []          # The map's globals.vars, every compile unit's.
        self.globals_main = "gs_globals_main"
        self.globals_thread = None   # "gs_gl" when the program starts threads.
        for d in docs:
            g = d.get("globals")
            if isinstance(g, dict):
                self.globals += g.get("vars", [])
                self.globals_main = g.get("main") or self.globals_main
                self.globals_thread = g.get("thread") or self.globals_thread
            for f in d.get("functions", []):
                self.funcs.setdefault(f.get("c"), f)
            for t in d.get("types", []):
                self.types.setdefault(t.get("name"), t)
                if t.get("c"):
                    self.types_by_c.setdefault(t["c"], t)

    def local(self, fname, cname):
        f = self.funcs.get(fname)
        if f is None:
            return None
        for l in f.get("locals", []):
            if l.get("c") == cname:
                return l
        return None


_map_cache = {}


def map_path(cpath):
    return cpath[:-2] + ".debug.json" if cpath.endswith(".c") else None


def get_map(target):
    """The target's debug map, or None: none written, older than its .c (a
    compiler without the map rebuilt the program since), unreadable, or
    turned off with GOOSE_LLDB_MAP=0."""
    if not _env_on("GOOSE_LLDB_MAP"):
        return None
    try:
        key = []
        for c in _cu_paths(target):
            j = map_path(c)
            try:
                cm, jm = os.path.getmtime(c), os.path.getmtime(j)
            except OSError:
                continue
            if jm + 1e-3 < cm:
                continue
            key.append((j, jm))
        key = tuple(key)
        if not key:
            return None
        if key in _map_cache:
            return _map_cache[key]
        docs = []
        for j, _ in key:
            try:
                with open(j) as f:
                    d = json.load(f)
                # The decoder builds in version 1's layouts; a later map may
                # describe others.
                if d.get("version") == 1:
                    docs.append(d)
            except Exception:
                pass
        m = GooseMap(docs) if docs else None
        _map_cache.clear()
        _map_cache[key] = m
        return m
    except Exception:
        return None


# Goose type text, as the map writes it, parsed into tuples:
#   ("scalar", "i64")            i8 ... u64, f32, f64, bool, varint
#   ("named", "Pair<i64>")       a struct or a fixed-mode enum
#   ("varmode", "Shape")         Shape..
#   ("variant", "Shape", "Circle")
#   ("array", elem, form, n)     form: fixed (n = k), var (n = count type),
#                                limited (n = k), cap, grow, shrink, slice
#   ("ref", t), ("opt", t)       T&, T?
#   ("rel", t, width, pool, optional)   T&<u32>, T&<u32 in pool>?

_SCALAR_SIZES = {"i8": 1, "u8": 1, "bool": 1, "i16": 2, "u16": 2, "i32": 4, "u32": 4,
                 "f32": 4, "i64": 8, "u64": 8, "f64": 8, "varint": None}
_COUNT_TYPES = ("u8", "u16", "u32", "u64", "varint")
_parse_cache = {}


def _open_of(s, close_idx, op, cl):
    depth = 0
    for i in range(close_idx, -1, -1):
        if s[i] == cl:
            depth += 1
        elif s[i] == op:
            depth -= 1
            if depth == 0:
                return i
    return -1


def _top_level_dot(s):
    """The index of the last '.' outside <...> that is not part of '..', or -1."""
    depth = 0
    for i in range(len(s) - 1, -1, -1):
        c = s[i]
        if c == ">":
            depth += 1
        elif c == "<":
            depth -= 1
        elif c == "." and depth == 0:
            if (i > 0 and s[i - 1] == ".") or (i + 1 < len(s) and s[i + 1] == "."):
                return -1
            return i
    return -1


def parse_type(text):
    t = _parse_cache.get(text)
    if t is None:
        t = _parse(text.strip())
        _parse_cache[text] = t
    return t


def _parse(s):
    if s.startswith("const "):
        return _parse(s[6:].strip())
    if s.endswith("?"):
        inner = _parse(s[:-1])
        if inner[0] == "ref":
            return ("opt", inner[1])
        if inner[0] == "rel":
            return inner[:4] + (True,)
        return ("opt", inner)
    if s.endswith(">"):
        i = _open_of(s, len(s) - 1, "<", ">")
        if i > 0 and s[i - 1] == "&":
            args = s[i + 1:-1].split(" in ")
            pool = args[1].strip() if len(args) > 1 else None
            return ("rel", _parse(s[:i - 1]), args[0].strip(), pool, False)
    if s.endswith("&"):
        return ("ref", _parse(s[:-1]))
    if s.endswith("]"):
        i = _open_of(s, len(s) - 1, "[", "]")
        inner = s[i + 1:-1].strip()
        elem = _parse(s[:i])
        if inner == "":
            return ("array", elem, "var", "u32")
        if inner == ":":
            return ("array", elem, "slice", None)
        if inner == ">..":
            return ("array", elem, "grow", None)
        if inner == ">..<":
            return ("array", elem, "shrink", None)
        if inner == "..":
            return ("array", elem, "cap", None)
        if inner.startswith(".."):
            return ("array", elem, "limited", int(inner[2:]))
        if inner in _COUNT_TYPES:
            return ("array", elem, "var", inner)
        return ("array", elem, "fixed", int(inner))
    if s.endswith(".."):
        return ("varmode", s[:-2])
    if s in _SCALAR_SIZES:
        return ("scalar", s)
    i = _top_level_dot(s)
    if i > 0:
        return ("variant", s[:i], s[i + 1:])
    return ("named", s)


def type_text(t):
    k = t[0]
    if k in ("scalar", "named"):
        return t[1]
    if k == "varmode":
        return t[1] + ".."
    if k == "variant":
        return t[1] + "." + t[2]
    if k == "ref":
        return type_text(t[1]) + "&"
    if k == "opt":
        return type_text(t[1]) + "?"
    if k == "rel":
        inner = t[2] + (" in " + t[3] if t[3] else "")
        return "%s&<%s>%s" % (type_text(t[1]), inner, "?" if t[4] else "")
    e = type_text(t[1])
    form, n = t[2], t[3]
    suffix = {"slice": "[:]", "grow": "[>..]", "shrink": "[>..<]", "cap": "[..]"}.get(form)
    if suffix:
        return e + suffix
    if form == "limited":
        return "%s[..%d]" % (e, n)
    if form == "fixed":
        return "%s[%d]" % (e, n)
    return e + ("[]" if n == "u32" else "[%s]" % n)


def _len_width(k):
    return 1 if k < 1 << 8 else 2 if k < 1 << 16 else 4 if k < 1 << 32 else 8


def _width_bytes(w):
    return {"u8": 1, "u16": 2, "u32": 4, "u64": 8}.get(w)


class Decoder:
    """Reads Goose values out of the inferior by their Goose type. Generated
    types are packed (#pragma pack 1), so a field's offset is the sum of the
    sizes before it. Every method may raise; callers catch."""

    def __init__(self, target, gmap):
        self.target = target
        self.process = target.GetProcess()
        self.map = gmap

    # -- memory

    def read(self, addr, n):
        if addr == 0:
            raise ValueError("null")
        err = lldb.SBError()
        data = self.process.ReadMemory(addr, n, err)
        if err.Fail() or data is None or len(data) != n:
            raise ValueError("unreadable at 0x%x" % addr)
        return bytes(data)

    def uint(self, addr, n):
        return int.from_bytes(self.read(addr, n), "little")

    def sint(self, addr, n):
        return int.from_bytes(self.read(addr, n), "little", signed=True)

    def uleb(self, addr):
        v, shift, i = 0, 0, 0
        while i < 10:
            b = self.uint(addr + i, 1)
            v |= (b & 0x7F) << shift
            shift += 7
            i += 1
            if not b & 0x80:
                return v, i
        raise ValueError("bad LEB128 at 0x%x" % addr)

    def varint(self, addr):
        u, n = self.uleb(addr)
        return (u >> 1) ^ -(u & 1), n

    # -- types

    def tdef(self, name):
        return self.map.types.get(name) if self.map else None

    def size(self, t):
        """The byte size of a fixed-size type, None for a variable one."""
        k = t[0]
        if k == "scalar":
            return _SCALAR_SIZES[t[1]]
        if k in ("ref", "opt"):
            return 8
        if k == "rel":
            return _width_bytes(t[2])
        if k == "array":
            form = t[2]
            if form in ("slice", "grow", "shrink"):
                return 16
            es = self.size(t[1])
            if es is None:
                return None
            if form == "fixed":
                return t[3] * es
            if form == "limited":
                return _len_width(t[3]) + t[3] * es
            return None
        if k == "variant":
            td = self.tdef(t[1])
            v = self._variant(td, t[2])
            return self._fields_size(v.get("fields", [])) if v is not None else None
        if k == "named":
            td = self.tdef(t[1])
            if td is None or td.get("variable"):
                return None
            if td.get("kind") == "enum":
                if not td.get("fixed"):
                    return None
                sizes = [self._fields_size(v.get("fields", [])) for v in td.get("variants", [])]
                if any(x is None for x in sizes):
                    return None
                return 1 + max(sizes + [0])
            return self._fields_size(td.get("fields", []))
        return None

    def _fields_size(self, fields):
        n = 0
        for f in fields:
            s = self.size(parse_type(f["type"]))
            if s is None:
                return None
            n += s
        return n

    @staticmethod
    def _variant(td, name):
        if td is None:
            return None
        for v in td.get("variants", []):
            if v.get("name") == name:
                return v
        return None

    @staticmethod
    def _variant_by_tag(td, tag):
        for v in td.get("variants", []):
            if v.get("tag") == tag:
                return v
        return None

    # -- values as text: (text, size); size is None where it is not known

    def text(self, addr, t, depth=0):
        k = t[0]
        if k == "scalar":
            return self._scalar(addr, t[1])
        if k in ("ref", "opt"):
            p = self.uint(addr, 8)
            if p == 0:
                return ("null", 8)
            return (self._target_text(p, t[1], depth), 8)
        if k == "rel":
            w = _width_bytes(t[2])
            if w is None:
                return ("<%s offset>" % t[2], None)
            target = self.rel_target(addr, t)
            if target is None:
                return ("null", w)
            return (self._target_text(target, t[1], depth), w)
        if k == "array":
            return self._array(addr, t, depth)
        if k == "named":
            td = self.tdef(t[1])
            if td is None:
                return ("<%s: not in the debug map>" % t[1], self.size(t))
            if td.get("kind") == "enum":
                return self._enum(addr, td, td.get("fixed", False), depth)
            body, n = self._fields(addr, td.get("fields", []), depth)
            return ("%s %s" % (td["name"], body), n)
        if k == "varmode":
            td = self.tdef(t[1])
            if td is None:
                return ("<%s..: not in the debug map>" % t[1], None)
            return self._enum(addr, td, False, depth)
        if k == "variant":
            td = self.tdef(t[1])
            v = self._variant(td, t[2])
            if v is None:
                return ("<%s.%s: not in the debug map>" % (t[1], t[2]), None)
            body, n = self._fields(addr, v.get("fields", []), depth)
            return ("%s.%s %s" % (td["name"], t[2], body) if v.get("fields") else "%s.%s" % (td["name"], t[2]), n)
        return ("?", None)

    def _target_text(self, addr, t, depth):
        # Depth counts the references followed, which is what can cycle.
        if depth >= MAX_DEPTH:
            return "0x%x" % addr
        return self.text(addr, t, depth + 1)[0]

    def rel_target(self, addr, t):
        """The address a relative reference at addr points to, or None for
        null. Self-relative: the field's own address plus the signed
        offset. Pool-relative: the pool's base, minus one, plus the offset."""
        w = _width_bytes(t[2])
        off = self.uint(addr, w)
        if off == 0 and t[4]:
            return None
        if t[3] is None:
            if off >= 1 << (8 * w - 1):
                off -= 1 << (8 * w)
            return addr + off
        base = self.pool_base(t[3])
        if base is None:
            raise ValueError("pool %s not found" % t[3])
        return base - 1 + off

    def pool_base(self, pool):
        g = self.target.FindFirstGlobalVariable("gs_globals_main")
        if not g.IsValid():
            return None
        v = g
        for part in pool.split("."):
            v = v.GetNonSyntheticValue().GetChildMemberWithName(part + "_g")
            if not v.IsValid():
                return None
        b = v.GetNonSyntheticValue().GetChildMemberWithName("base")
        return b.GetValueAsUnsigned() if b.IsValid() else None

    def _scalar(self, addr, name):
        if name == "varint":
            v, n = self.varint(addr)
            return (str(v), n)
        n = _SCALAR_SIZES[name]
        if name == "bool":
            return ("true" if self.uint(addr, 1) else "false", 1)
        if name == "f64":
            return (_fmt_float(struct.unpack("<d", self.read(addr, 8))[0], False), 8)
        if name == "f32":
            return (_fmt_float(struct.unpack("<f", self.read(addr, 4))[0], True), 4)
        if name.startswith("i"):
            return (str(self.sint(addr, n)), n)
        return (str(self.uint(addr, n)), n)

    def array_layout(self, addr, t):
        """(address of the first element, element count, byte size of the
        array where it is stored, or None)."""
        form, n = t[2], t[3]
        if form == "fixed":
            es = self.size(t[1])
            return addr, n, (n * es if es is not None else None)
        if form == "limited":
            w = _len_width(n)
            es = self.size(t[1])
            return addr + w, self.uint(addr, w), (w + n * es if es is not None else None)
        if form in ("slice", "grow", "shrink"):
            return self.uint(addr, 8), self.sint(addr + 8, 8), 16
        if form == "cap":
            cap, ln = self.uint(addr, 4), self.uint(addr + 4, 4)
            es = self.size(t[1])
            return addr + 8, ln, (8 + cap * es if es is not None else None)
        # var: a count, then the elements; the size is known after a walk.
        if n == "varint":
            cnt, w = self.uleb(addr)
        else:
            w = _width_bytes(n)
            cnt = self.uint(addr, w)
        return addr + w, cnt, None

    def elements(self, addr, t, limit):
        """[(address, element type)] for at most limit elements, the total
        count, and the array's stored size (None where unknown)."""
        first, cnt, stored = self.array_layout(addr, t)
        if cnt < 0 or cnt > MAX_LEN:
            raise ValueError("invalid len=%d" % cnt)
        et = t[1]
        es = self.size(et)
        out = []
        a = first
        for i in range(min(cnt, limit)):
            out.append((a, et))
            if es is None:
                _, sz = self.text(a, et, MAX_DEPTH)
                if sz is None:
                    break
                a += sz
            else:
                a += es
        if stored is None and t[2] == "var":
            if es is not None:
                stored = (first - addr) + cnt * es
            elif len(out) == cnt:
                stored = a - addr
        return out, cnt, stored

    def _array(self, addr, t, depth):
        et = t[1]
        if et == ("scalar", "u8"):
            first, cnt, stored = self.array_layout(addr, t)
            if cnt < 0 or cnt > MAX_LEN:
                return ("<invalid len=%d>" % cnt, stored)
            data = self.read(first, min(cnt, MAX_TEXT)) if cnt else b""
            if stored is None and t[2] == "var":
                stored = (first - addr) + cnt
            return (quote_bytes(bytearray(data), cnt), stored)
        els, cnt, stored = self.elements(addr, t, MAX_INLINE)
        parts = [self.text(a, et, depth)[0] for a, et in els]
        if cnt > len(els):
            parts.append("... (%d elements)" % cnt)
        return ("[" + ", ".join(parts) + "]", stored)

    def _fields(self, addr, fields, depth):
        """'{ x: 3, y: -4 }' and the fields' total size."""
        parts = []
        a = addr
        for f in fields:
            ft = parse_type(f["type"])
            txt, sz = self.text(a, ft, depth)
            parts.append("%s: %s" % (f["name"], txt))
            if sz is None:
                if len(parts) < len(fields):
                    parts.append("...")
                return ("{ " + ", ".join(parts) + " }", None)
            a += sz
        return ("{ " + ", ".join(parts) + " }" if parts else "{}", a - addr)

    def field_addrs(self, addr, fields):
        """[(name, address, type)] for the fields that can be located."""
        out = []
        a = addr
        for f in fields:
            ft = parse_type(f["type"])
            out.append((f["name"], a, ft))
            sz = self.size(ft)
            if sz is None:
                sz = self.text(a, ft, MAX_DEPTH)[1]
                if sz is None:
                    break
            a += sz
        return out

    def _enum(self, addr, td, fixed, depth):
        tag = self.uint(addr, 1)
        v = self._variant_by_tag(td, tag)
        size = self.size(("named", td["name"])) if fixed else None
        if v is None:
            return ("%s #%d" % (td["name"], tag), size)
        name = "%s.%s" % (td["name"], v["name"])
        if not v.get("fields"):
            return (name, size if fixed else 1)
        body, n = self._fields(addr + 1, v["fields"], depth)
        return ("%s %s" % (name, body), size if fixed else (1 + n if n is not None else None))

    # -- children: [(name, address, type)]

    def children(self, addr, t):
        k = t[0]
        if k in ("ref", "opt"):
            p = self.uint(addr, 8)
            return self.children(p, t[1]) if p else []
        if k == "rel":
            target = self.rel_target(addr, t)
            return self.children(target, t[1]) if target is not None else []
        if k == "array":
            els, cnt, _ = self.elements(addr, t, MAX_CHILDREN)
            return [("[%d]" % i, a, et) for i, (a, et) in enumerate(els)]
        if k == "named":
            td = self.tdef(t[1])
            if td is None:
                return []
            if td.get("kind") == "enum":
                return self._enum_children(addr, td)
            return self.field_addrs(addr, td.get("fields", []))
        if k == "varmode":
            td = self.tdef(t[1])
            return self._enum_children(addr, td) if td else []
        if k == "variant":
            v = self._variant(self.tdef(t[1]), t[2])
            return self.field_addrs(addr, v.get("fields", [])) if v else []
        return []

    def _enum_children(self, addr, td):
        v = self._variant_by_tag(td, self.uint(addr, 1))
        if v is None:
            return []
        return self.field_addrs(addr + 1, v.get("fields", []))


def _fmt_float(x, single):
    if x != x:
        return "nan"
    if x in (float("inf"), float("-inf")):
        return "inf" if x > 0 else "-inf"
    s = repr(x)
    if single:
        for p in range(1, 10):
            c = "%.*g" % (p, x)
            if struct.unpack("<f", struct.pack("<f", float(c)))[0] == x:
                s = c
                break
    if not any(ch in s for ch in ".en"):
        s += ".0"
    return s


# C types for Goose types, for the children a debugger UI lists: a child
# with a C type is an ordinary value of that type; one without (a bool, a
# varint, a reference, a variable-size value) is a uint8_t * whose summary
# decodes it.
_C_SCALARS = {"i8": "int8_t", "u8": "uint8_t", "i16": "int16_t", "u16": "uint16_t",
              "i32": "int32_t", "u32": "uint32_t", "i64": "int64_t", "u64": "uint64_t",
              "f32": "float", "f64": "double"}


def c_type_for(target, gmap, t):
    k = t[0]
    name = None
    if k == "scalar":
        name = _C_SCALARS.get(t[1])
    elif k == "named":
        td = gmap.types.get(t[1]) if gmap else None
        name = td.get("c") if td and not td.get("variable") else None
    elif k == "array" and t[2] in ("grow", "shrink"):
        name = "gs_rhdr"
    elif k == "array" and t[2] in ("slice", "fixed", "limited"):
        # sl_u8, a4_i32, l16_u8, a4_Shape_g: the element as the C names it.
        e = t[1]
        en = e[1] if e[0] == "scalar" else None
        if e[0] == "named" and gmap and gmap.types.get(e[1]):
            en = gmap.types[e[1]].get("c") or None
        if en:
            name = ("sl_%s" % en if t[2] == "slice" else
                    "a%d_%s" % (t[3], en) if t[2] == "fixed" else "l%d_%s" % (t[3], en))
    elif k in ("ref", "opt") and t[1][0] == "named":
        # A pointer to a struct shows its target through the struct's own
        # summary; one to a scalar would not, so it gets a uint8_t *.
        target_ct = c_type_for(target, gmap, t[1])
        return target_ct.GetPointerType() if target_ct is not None else None
    if not name:
        return None
    ct = target.FindFirstType(name)
    return ct if ct.IsValid() else None


# Values made for children carry their Goose type here, keyed by where they
# are and what they are called, so that their own summary and children can
# find it again.
_registry = {}


def _register(key, t):
    if len(_registry) > 20000:
        _registry.clear()
    _registry[key] = t


def goose_type_of(valobj, gmap):
    """A value's Goose type, parsed, or None: a child this module made, a
    local of the frame it belongs to, or a value of a C type the map names."""
    try:
        name = valobj.GetName() or ""
        tn = type_name(valobj.GetType())
        la = valobj.GetLoadAddress()
        if tn == "uint8_t *":
            t = _registry.get(("p", valobj.GetValueAsUnsigned(), name))
        else:
            t = _registry.get((la, tn, name))
        if t is not None:
            return t
        frame = valobj.GetFrame()
        if frame.IsValid():
            l = gmap.local(frame.GetFunctionName(), name)
            if l is not None:
                fv = frame.FindVariable(name)
                if fv.IsValid() and fv.GetLoadAddress() == la:
                    return parse_type(l["type"])
            t = _type_by_path(valobj, frame, gmap)
            if t is not None:
                return t
        td = gmap.types_by_c.get(tn)
        if td is not None:
            return ("named", td["name"])
    except Exception:
        pass
    return None


_PATH_RE = re.compile(r"\.|->|\[[0-9]+\]|[^.\-\[]+")


def _type_by_path(valobj, frame, gmap):
    """The Goose type of a C field or element reached from a frame local,
    such as s_g.items_g or u_g.pos_g.x_g, by walking the local's Goose type
    along the value's expression path. None where the walk leaves what the
    map describes (a slice's data, a header's base)."""
    st = lldb.SBStream()
    if not valobj.GetExpressionPath(st):
        return None
    toks = [x for x in _PATH_RE.findall(st.GetData() or "") if x not in (".", "->")]
    if len(toks) < 2:
        return None
    l = gmap.local(frame.GetFunctionName(), toks[0])
    rest = toks[1:]
    if l is None and toks[0] == gmap.globals_main:
        # A global: a field of the main program's globals block.
        l = next((g for g in gmap.globals if g.get("c") == toks[1]), None)
        rest = toks[2:]
    if l is None:
        return None
    t = parse_type(l["type"])
    variant = None
    for tok in rest:
        while t[0] in ("ref", "opt"):
            t = t[1]
        if tok.startswith("["):
            if t[0] != "array":
                return None
            t = t[1]
            continue
        if t[0] == "array" and tok == "e":
            continue
        if t[0] in ("named", "variant"):
            td = gmap.types.get(t[1])
            if td is None:
                return None
            if td.get("kind") == "enum":
                if tok == "u":
                    continue
                if t[0] == "named" and tok.startswith("v_") and variant is None:
                    variant = goose_name(tok[2:])
                    t = ("variant", t[1], variant)
                    continue
                v = Decoder._variant(td, t[2] if t[0] == "variant" else variant)
                fields = v.get("fields", []) if v else []
            else:
                fields = td.get("fields", [])
            variant = None
            f = next((f for f in fields if f.get("c") == tok), None)
            if f is None:
                return None
            t = parse_type(f["type"])
            continue
        return None
    return t


def value_location(valobj, t):
    """Where a value of Goose type t that LLDB holds as valobj starts in
    memory, or None. A variable-size value is a uint8_t * to its bytes; a
    reference or optional, and gs_rref, hold the pointer in place."""
    if type_name(valobj.GetType()) == "uint8_t *":
        # A variable-size local, or a child this module made for a value
        # with no C type of its own: either way the pointer is the address.
        return valobj.GetValueAsUnsigned()
    canon = valobj.GetType().GetCanonicalType()
    if canon.IsPointerType() and t[0] not in ("ref", "opt"):
        return valobj.GetValueAsUnsigned()
    la = valobj.GetLoadAddress()
    if la == lldb.LLDB_INVALID_ADDRESS:
        return None
    return la


def map_summary(valobj):
    """The summary decoded through the debug map, or None to fall back."""
    target = valobj.GetTarget()
    gmap = get_map(target)
    if gmap is None:
        return None
    v = valobj.GetNonSyntheticValue()
    t = goose_type_of(v, gmap)
    if t is None:
        return None
    addr = value_location(v, t)
    if addr is None:
        return None
    try:
        return Decoder(target, gmap).text(addr, t)[0]
    except Exception as e:
        return "<%s>" % e


def map_children(valobj):
    """Children decoded through the debug map, or None to fall back."""
    target = valobj.GetTarget()
    gmap = get_map(target)
    if gmap is None:
        return None
    v = valobj.GetNonSyntheticValue()
    t = goose_type_of(v, gmap)
    if t is None:
        return None
    addr = value_location(v, t)
    if addr is None:
        return None
    dec = Decoder(target, gmap)
    try:
        kids = dec.children(addr, t)
    except Exception:
        return []
    out = []
    u8p = target.FindFirstType("uint8_t").GetPointerType()
    order = target.GetByteOrder()
    for name, a, ct_g in kids[:MAX_CHILDREN]:
        ct = c_type_for(target, gmap, ct_g)
        if ct is not None:
            c = v.CreateValueFromAddress(name, a, ct)
            _register((a, type_name(ct), name), ct_g)
        else:
            data = lldb.SBData.CreateDataFromUInt64Array(order, 8, [a])
            c = v.CreateValueFromData(name, data, u8p)
            _register(("p", a, name), ct_g)
        out.append(c)
    return out


# --------------------------------------------------------------------------
# Synthetic children


class GooseSynth:
    """Children for each kind: the elements of an array or slice, the target's
    children for a reference, the tag and active payload of an enum."""

    def __init__(self, valobj, internal_dict):
        self.valobj = valobj
        self.kind = None
        self.kids = []
        try:
            self.kind = classify(valobj.GetType())
        except Exception:
            pass

    def update(self):
        self.kids = []
        try:
            kids = map_children(self.valobj)
            self.kids = kids if kids is not None else self._children()
        except Exception:
            self.kids = []
        return False

    def _raw(self):
        v = self.valobj.GetNonSyntheticValue()
        return [v.GetChildAtIndex(i) for i in range(v.GetNumChildren())]

    def _children(self):
        v = self.valobj.GetNonSyntheticValue()
        k = self.kind
        if k == "slice":
            data = v.GetChildMemberWithName("data")
            n = v.GetChildMemberWithName("len").GetValueAsSigned()
            et = data.GetType().GetPointeeType()
            return self._elements(data.GetValueAsUnsigned(), et, n)
        if k == "fixed":
            e = v.GetChildMemberWithName("e")
            return [e.GetChildAtIndex(i) for i in range(min(e.GetNumChildren(), MAX_CHILDREN))]
        if k == "limited":
            e = v.GetChildMemberWithName("e")
            n = v.GetChildMemberWithName("len").GetValueAsUnsigned()
            n = min(n, e.GetNumChildren(), MAX_CHILDREN)
            return [e.GetChildAtIndex(i) for i in range(n)]
        if k in ("ref", "opt"):
            if v.GetValueAsUnsigned() == 0:
                return []
            d = v.Dereference()
            if d.GetNumChildren() == 0:
                return [d.Clone("*")]
            return [d.GetChildAtIndex(i) for i in range(min(d.GetNumChildren(), MAX_CHILDREN))]
        if k == "adt":
            tag = v.GetChildMemberWithName("tag")
            kids = [tag]
            name = tag_name(v, _cname(v), tag.GetValueAsUnsigned())
            u = v.GetChildMemberWithName("u")
            if name is not None:
                p = u.GetChildMemberWithName("v_%s_g" % name)
                if p.IsValid():
                    kids.append(p)
            else:
                kids.append(u)
            return kids
        return self._raw()

    def _elements(self, addr, et, n):
        if n <= 0 or n > MAX_LEN or addr == 0:
            return []
        size = et.GetByteSize()
        if size == 0:
            return []
        out = []
        for i in range(min(n, MAX_CHILDREN)):
            out.append(self.valobj.CreateValueFromAddress("[%d]" % i, addr + i * size, et))
        return out

    def num_children(self):
        return len(self.kids)

    def get_child_index(self, name):
        try:
            if name.startswith("[") and name.endswith("]"):
                return int(name[1:-1])
            for i, c in enumerate(self.kids):
                if c.GetName() == name:
                    return i
            # A C path (s_g.items_g) to a child the map named in Goose.
            g = goose_name(name)
            for i, c in enumerate(self.kids):
                if c.GetName() == g:
                    return i
        except Exception:
            pass
        return -1

    def get_child_at_index(self, index):
        if 0 <= index < len(self.kids):
            return self.kids[index]
        return None

    def has_children(self):
        return len(self.kids) > 0


def _cname(v):
    return type_name(v.GetType())


# --------------------------------------------------------------------------
# Summaries


def summary(valobj, internal_dict, options=None):
    try:
        s = _summary(valobj)
        return _clip(s) if s is not None else ""
    except Exception as e:
        return "<goose_lldb: %s>" % type(e).__name__


def _summary(valobj):
    s = map_summary(valobj)
    if s is not None:
        return s
    v = valobj.GetNonSyntheticValue()
    k = classify(v.GetType())
    proc = v.GetProcess()
    if k == "bytes":
        # In a Goose program a uint8_t * is a variable-size value (T[], a
        # variable-mode enum, a struct with varint fields) or a data-stack
        # top, never a NUL-terminated string: LLDB's C-string summary
        # misleads.
        return "<untyped bytes>"
    if k is None and v.GetType().GetCanonicalType().IsPointerType():
        # A cascade through a pointer: a struct field Node_g *next_g, which
        # is a Node& or a Node? (DWARF does not say which), or a gs_rhdr *.
        if v.GetValueAsUnsigned() == 0:
            return "null"
        d = v.Dereference()
        if d.GetError().Fail() or classify(d.GetType()) is None:
            return None
        return _summary(d)

    if k == "slice":
        data = v.GetChildMemberWithName("data")
        n = v.GetChildMemberWithName("len").GetValueAsSigned()
        if n < 0 or n > MAX_LEN:
            return "<invalid len=%d>" % n
        et = data.GetType().GetPointeeType()
        if _is_u8(et):
            return read_text(proc, data.GetValueAsUnsigned(), n)
        if data.GetValueAsUnsigned() == 0 and n != 0:
            return "<null data, len=%d>" % n
        kids = [valobj.CreateValueFromAddress("[%d]" % i, data.GetValueAsUnsigned() + i * et.GetByteSize(), et)
                for i in range(min(n, MAX_INLINE))]
        return _list_text(kids, n)

    if k == "fixed":
        e = v.GetChildMemberWithName("e")
        n = e.GetNumChildren()
        if _is_u8(e.GetType().GetArrayElementType()):
            return quote_bytes(bytearray(e.GetChildAtIndex(i).GetValueAsUnsigned() for i in range(min(n, MAX_TEXT))), n)
        return _list_text([e.GetChildAtIndex(i) for i in range(min(n, MAX_INLINE))], n)

    if k == "limited":
        e = v.GetChildMemberWithName("e")
        cap = e.GetNumChildren()
        n = v.GetChildMemberWithName("len").GetValueAsUnsigned()
        if n > cap:
            return "<invalid len=%d, cap=%d>" % (n, cap)
        if _is_u8(e.GetType().GetArrayElementType()):
            return quote_bytes(bytearray(e.GetChildAtIndex(i).GetValueAsUnsigned() for i in range(min(n, MAX_TEXT))), n)
        return _list_text([e.GetChildAtIndex(i) for i in range(min(n, MAX_INLINE))], n)

    if k == "rhdr":
        n = v.GetChildMemberWithName("len").GetValueAsSigned()
        if n < 0 or n > MAX_LEN:
            return "<invalid len=%d>" % n
        return "len=%d, element type not in debug info" % n

    if k == "rref":
        h = v.GetChildMemberWithName("hdr")
        if h.GetValueAsUnsigned() == 0:
            return "null"
        n = h.Dereference().GetChildMemberWithName("len").GetValueAsSigned()
        if n < 0 or n > MAX_LEN:
            return "<invalid len=%d>" % n
        return "len=%d, element type not in debug info" % n

    if k in ("ref", "opt"):
        if v.GetValueAsUnsigned() == 0:
            return "null"
        d = v.Dereference()
        if d.GetError().Fail():
            return "<unreadable 0x%x>" % v.GetValueAsUnsigned()
        return value_text(d)

    if k == "cenum":
        cn = _cname(v)
        tag = v.GetChildMemberWithName("tag").GetValueAsUnsigned()
        name = tag_name(v, cn, tag)
        if name is None:
            return "%s #%d" % (goose_type_name(cn), tag)
        return "%s.%s" % (goose_type_name(cn), name)

    if k == "adt":
        cn = _cname(v)
        tag = v.GetChildMemberWithName("tag").GetValueAsUnsigned()
        name = tag_name(v, cn, tag)
        tn = goose_type_name(cn)
        if name is None:
            return "%s #%d (tag names not in debug info)" % (tn, tag)
        p = v.GetChildMemberWithName("u").GetChildMemberWithName("v_%s_g" % name)
        if not p.IsValid():
            return "%s.%s" % (tn, name)
        return "%s.%s %s" % (tn, name, _fields_text(p))

    if k == "payload":
        cn = _cname(v)
        ename, variant = payload_owner(v, cn)
        prefix = "%s.%s" % (goose_type_name(ename), variant) if ename else cn
        return "%s %s" % (prefix, _fields_text(v))

    if k == "struct":
        return "%s %s" % (goose_type_name(_cname(v)), _fields_text(v))

    return None


def _fields_text(v):
    parts = []
    for i in range(min(v.GetNumChildren(), MAX_INLINE)):
        c = v.GetChildAtIndex(i)
        parts.append("%s: %s" % (goose_name(c.GetName()), value_text(c)))
    if v.GetNumChildren() > MAX_INLINE:
        parts.append("...")
    return "{ " + ", ".join(parts) + " }" if parts else "{}"


# --------------------------------------------------------------------------
# Commands


def cmd_locals(debugger, command, exe_ctx, result, internal_dict):
    """goose-locals: the selected frame's Goose locals and arguments under
    their Goose names, without compiler temporaries (tN) and hidden arguments
    (gs_sp). With the debug map: `name: type = value`, only the locals in
    scope at the frame's line, decoded by their Goose type."""
    try:
        frame = exe_ctx.GetFrame()
        if not frame.IsValid():
            result.SetError("no frame selected")
            return
        gmap = get_map(exe_ctx.GetTarget())
        f = gmap.funcs.get(frame.GetFunctionName()) if gmap else None
        if f is not None:
            _locals_from_map(frame, gmap, f, result)
            return
        vs = frame.GetVariables(True, True, False, True)
        for i in range(vs.GetSize()):
            v = vs.GetValueAtIndex(i)
            cn = v.GetName() or ""
            if not _NAME_RE.match(cn):
                continue
            k = classify(v.GetType())
            if k == "bytes":
                s = "0x%x" % v.GetValueAsUnsigned()
            elif k is not None:
                s = v.GetSummary() or value_text(v)
            elif v.GetType().GetCanonicalType().IsPointerType():
                s = "0x%x" % v.GetValueAsUnsigned()
            else:
                s = value_text(v)
            result.AppendMessage("%s = %s" % (goose_name(cn), s))
    except Exception as e:
        result.SetError("goose-locals: %s" % e)


def _locals_from_map(frame, gmap, f, result):
    line = frame.GetLineEntry().GetLine()
    dec = Decoder(frame.GetThread().GetProcess().GetTarget(), gmap)
    ls = [l for l in f.get("locals", []) if l.get("line", 0) <= line <= l.get("end", 0)]
    ls.sort(key=lambda l: (not l.get("param"), l.get("line", 0)))
    for l in ls:
        v = frame.FindVariable(l["c"])
        if not v.IsValid():
            result.AppendMessage("%s: %s = <not in the debug info>" % (l["name"], l["type"]))
            continue
        t = parse_type(l["type"])
        addr = value_location(v.GetNonSyntheticValue(), t)
        try:
            txt = dec.text(addr, t)[0] if addr is not None else value_text(v)
        except Exception as e:
            txt = "<%s>" % e
        result.AppendMessage("%s: %s = %s" % (l["name"], l["type"], _clip(txt)))


def cmd_globals(debugger, command, exe_ctx, result, internal_dict):
    """goose-globals: the program's Goose globals, `name: type = value`, from
    the debug map: the main program's instance, or under threads the
    selected thread's own (gs_gl)."""
    try:
        target = exe_ctx.GetTarget()
        gmap = get_map(target)
        if gmap is None or not gmap.globals:
            result.AppendMessage("no globals in the debug map" if gmap else "no debug map")
            return
        block = None
        frame = exe_ctx.GetFrame()
        if gmap.globals_thread and frame.IsValid():
            p = frame.EvaluateExpression("(gs_globals_t *)%s" % gmap.globals_thread)
            if p.IsValid() and not p.GetError().Fail() and p.GetValueAsUnsigned():
                block = p.Dereference()
        if block is None:
            block = target.FindFirstGlobalVariable(gmap.globals_main)
        if not block.IsValid():
            result.SetError("no %s in the debug info" % gmap.globals_main)
            return
        dec = Decoder(target, gmap)
        for g in gmap.globals:
            v = block.GetChildMemberWithName(g["c"])
            if not v.IsValid():
                result.AppendMessage("%s: %s = <not in the debug info>" % (g["name"], g["type"]))
                continue
            t = parse_type(g["type"])
            addr = value_location(v.GetNonSyntheticValue(), t)
            try:
                txt = dec.text(addr, t)[0] if addr is not None else value_text(v)
            except Exception as e:
                txt = "<%s>" % e
            result.AppendMessage("%s: %s = %s" % (g["name"], g["type"], _clip(txt)))
    except Exception as e:
        result.SetError("goose-globals: %s" % e)


def _goose_frame_index(thread):
    """The innermost frame on a .goose line, or None."""
    for i in range(thread.GetNumFrames()):
        fs = thread.GetFrameAtIndex(i).GetLineEntry().GetFileSpec()
        if (fs.GetFilename() or "").endswith(".goose"):
            return i
    return None


def on_runtime_error(frame, bp_loc, internal_dict):
    """goose-catch's breakpoint callback, in gs_fail, where every Goose
    runtime error (a failed check, assert or abort) ends: prints the error
    as the runtime will, and selects the Goose frame that failed, so bt,
    goose-locals and frame variable start there (CatchHook selects it: a
    callback's selection does not outlast the stop). Stops."""
    try:
        err = lldb.SBError()
        proc = frame.GetThread().GetProcess()
        msg, file_, line = "", "", 0
        m, n = frame.FindVariable("msg"), frame.FindVariable("len")
        if m.IsValid() and n.IsValid():
            k = n.GetValueAsSigned()
            if 0 < k <= MAX_TEXT:
                b = proc.ReadMemory(m.GetValueAsUnsigned(), k, err)
                if not err.Fail():
                    msg = b.decode("utf-8", "replace")
        f, l = frame.FindVariable("file"), frame.FindVariable("line")
        if f.IsValid() and f.GetValueAsUnsigned():
            file_ = proc.ReadCStringFromMemory(f.GetValueAsUnsigned(), 1024, err) or ""
        if l.IsValid():
            line = l.GetValueAsSigned()
        where = " (%s:%d)" % (file_, line) if file_ else ""
        print("goose runtime error: %s%s" % (msg or "<message not in the debug info>", where))
    except Exception as e:
        print("goose-catch: %s" % e)
    return True


class CatchHook:
    """goose-catch's stop hook: when a thread stops in gs_fail, selects the
    Goose frame that failed."""

    def __init__(self, target, extra_args, internal_dict):
        pass

    def handle_stop(self, exe_ctx, stream):
        try:
            thread = exe_ctx.GetThread()
            if thread.IsValid() and thread.GetFrameAtIndex(0).GetFunctionName() == "gs_fail":
                i = _goose_frame_index(thread)
                if i is not None:
                    thread.SetSelectedFrame(i)
        except Exception:
            pass
        return True


_catch_hooks = {}   # Target -> the stop hook's id.


def cmd_catch(debugger, command, exe_ctx, result, internal_dict):
    """goose-catch [off]: stop on Goose runtime errors -- a failed bounds,
    overflow or tag check, an assert, an abort -- at the Goose line that
    failed, rather than letting the program exit; `off` removes it."""
    try:
        target = exe_ctx.GetTarget() if exe_ctx.GetTarget().IsValid() else debugger.GetSelectedTarget()
        for i in reversed(range(target.GetNumBreakpoints())):
            bp = target.GetBreakpointAtIndex(i)
            names = lldb.SBStringList()
            bp.GetNames(names)
            if any(names.GetStringAtIndex(k) == "goose-catch" for k in range(names.GetSize())):
                target.BreakpointDelete(bp.GetID())
        key = target.GetExecutable().fullpath
        if command.strip() == "off":
            if key in _catch_hooks:
                debugger.HandleCommand("target stop-hook delete %d" % _catch_hooks.pop(key))
            result.AppendMessage("goose-catch off")
            return
        if key not in _catch_hooks:
            ret = lldb.SBCommandReturnObject()
            debugger.GetCommandInterpreter().HandleCommand(
                "target stop-hook add -P %s.CatchHook" % __name__, ret)
            m = re.search(r"#(\d+)", ret.GetOutput() or "")
            if m:
                _catch_hooks[key] = int(m.group(1))
        bp = target.BreakpointCreateByName("gs_fail")
        bp.AddName("goose-catch")
        bp.SetScriptCallbackFunction("%s.on_runtime_error" % __name__)
        result.AppendMessage("goose-catch: stops on Goose runtime errors (%d location%s)"
                             % (bp.GetNumLocations(), "" if bp.GetNumLocations() == 1 else "s"))
    except Exception as e:
        result.SetError("goose-catch: %s" % e)


def frame_name(frame, internal_dict=None):
    """A frame's function under its Goose name, with the type arguments of a
    generic instance: from the debug map, else the C name. For frame-format:
    ${script.frame:goose_lldb.frame_name}."""
    try:
        cname = frame.GetFunctionName() or ""
        gmap = get_map(frame.GetThread().GetProcess().GetTarget())
        f = gmap.funcs.get(cname) if gmap else None
        if f is None:
            return cname or (frame.GetSymbol().GetName() or "???")
        b = dict(f.get("bindings") or {})
        b.update(f.get("fnvals") or {})
        if b:
            return "%s<%s>" % (f["name"], ", ".join("%s=%s" % kv for kv in b.items()))
        return f["name"]
    except Exception:
        return frame.GetFunctionName() or "???"


def cmd_bt(debugger, command, exe_ctx, result, internal_dict):
    """goose-bt [N]: the selected thread's frames under their Goose names
    (from the debug map), with Goose files and lines; at most N frames."""
    try:
        thread = exe_ctx.GetThread()
        if not thread.IsValid():
            result.SetError("no thread selected")
            return
        n = int(command.strip()) if command.strip() else thread.GetNumFrames()
        sel = thread.GetSelectedFrame().GetFrameID()
        for i in range(min(n, thread.GetNumFrames())):
            fr = thread.GetFrameAtIndex(i)
            le = fr.GetLineEntry()
            where = ""
            if le.IsValid() and le.GetFileSpec().GetFilename():
                where = " at %s:%d" % (le.GetFileSpec().GetFilename(), le.GetLine())
            mod = fr.GetModule().GetFileSpec().GetFilename() or "?"
            mark = "*" if i == sel else " "
            result.AppendMessage("%s #%d: %s`%s%s" % (mark, i, mod, frame_name(fr), where))
    except Exception as e:
        result.SetError("goose-bt: %s" % e)


FRAME_FORMAT = ("frame #${frame.index}: ${ansi.fg.yellow}${frame.pc}${ansi.normal}"
                "{ ${module.file.basename}{`${script.frame:%s.frame_name}}}"
                "{ at ${ansi.fg.cyan}${line.file.basename}${ansi.normal}:"
                "${ansi.fg.yellow}${line.number}${ansi.normal}}\\n")


def cmd_frame_format(debugger, command, exe_ctx, result, internal_dict):
    """goose-frame-format [on|off]: makes LLDB's own frame lines (bt,
    stops) show Goose function names, or puts back LLDB's default."""
    arg = command.strip() or "on"
    if arg == "off":
        debugger.HandleCommand("settings clear frame-format")
    else:
        debugger.HandleCommand('settings set frame-format "%s"' % (FRAME_FORMAT % __name__))
    result.AppendMessage("frame-format %s" % ("default" if arg == "off" else "shows Goose names"))


_SCALARS = {
    "i8": "int8_t", "i16": "int16_t", "i32": "int32_t", "i64": "int64_t",
    "u8": "uint8_t", "u16": "uint16_t", "u32": "uint32_t", "u64": "uint64_t",
    "f32": "float", "f64": "double", "bool": "uint8_t",
}


def cmd_as(debugger, command, exe_ctx, result, internal_dict):
    """goose-as VAR TYPE: shows VAR's elements as TYPE, which the debug info
    cannot say. VAR is a resizable (gs_rhdr, gs_rref: TYPE is the element
    type) or a uint8_t * holding a T[] (TYPE written as T[]: a u32 count,
    then the elements). TYPE is a Goose scalar (i64, u8, f64, ...) or a C
    type name (Point_g, Shape_g, a4_i32)."""
    try:
        args = command.split()
        if len(args) != 2:
            result.SetError("usage: goose-as VAR TYPE  (TYPE: i64, Point_g, ... or T[] for a T[])")
            return
        path, tname = args
        frozen = tname.endswith("[]")
        if frozen:
            tname = tname[:-2]
        frame = exe_ctx.GetFrame()
        v = frame.GetValueForVariablePath(path)
        if not v.IsValid():
            v = exe_ctx.GetTarget().EvaluateExpression(path)
        if not v.IsValid() or v.GetError().Fail():
            result.SetError("no variable %s" % path)
            return
        et = exe_ctx.GetTarget().FindFirstType(_SCALARS.get(tname, tname))
        if not et.IsValid() or et.GetByteSize() == 0:
            result.SetError("unknown type %s" % tname)
            return
        v = v.GetNonSyntheticValue()
        k = classify(v.GetType())
        if frozen:
            addr = v.GetValueAsUnsigned()
            err = lldb.SBError()
            n = exe_ctx.GetProcess().ReadUnsignedFromMemory(addr, 4, err)
            if err.Fail():
                result.SetError("cannot read the count at 0x%x" % addr)
                return
            addr += 4
        elif k in ("rhdr", "rref"):
            h = v if k == "rhdr" else v.GetChildMemberWithName("hdr").Dereference()
            addr = h.GetChildMemberWithName("base").GetValueAsUnsigned()
            n = h.GetChildMemberWithName("len").GetValueAsSigned()
        else:
            result.SetError("%s is not a resizable; for a T[] write the type as %s[]" % (path, tname))
            return
        if n < 0 or n > MAX_LEN:
            result.SetError("invalid length %d" % n)
            return
        size = et.GetByteSize()
        if _is_u8(et):
            result.AppendMessage("len=%d %s" % (n, read_text(exe_ctx.GetProcess(), addr, n)))
            return
        result.AppendMessage("len=%d" % n)
        for i in range(min(n, MAX_CHILDREN)):
            c = v.CreateValueFromAddress("[%d]" % i, addr + i * size, et)
            result.AppendMessage("  [%d] = %s" % (i, value_text(c)))
        if n > MAX_CHILDREN:
            result.AppendMessage("  ... (%d more)" % (n - MAX_CHILDREN))
    except Exception as e:
        result.SetError("goose-as: %s" % e)


# --------------------------------------------------------------------------
# Registration


def __lldb_init_module(debugger, internal_dict):
    mod = __name__
    if debugger.GetCategory(CATEGORY).IsValid():
        debugger.DeleteCategory(CATEGORY)
    cat = debugger.CreateCategory(CATEGORY)
    spec = lldb.SBTypeNameSpecifier("%s.recognize" % mod, lldb.eFormatterMatchCallback)
    opts = lldb.eTypeOptionCascade | lldb.eTypeOptionHideChildren
    cat.AddTypeSummary(spec, lldb.SBTypeSummary.CreateWithFunctionName("%s.summary" % mod, opts))
    cat.AddTypeSynthetic(spec, lldb.SBTypeSynthetic.CreateWithClassName(
        "%s.GooseSynth" % mod, lldb.eTypeOptionCascade))
    # u8 is a number in Goose; there is no char type.
    for t in ("uint8_t", "int8_t"):
        cat.AddTypeFormat(lldb.SBTypeNameSpecifier(t, False),
                          lldb.SBTypeFormat(lldb.eFormatDecimal,
                                            lldb.eTypeOptionCascade | lldb.eTypeOptionSkipPointers
                                            | lldb.eTypeOptionSkipReferences))
    cat.SetEnabled(True)
    debugger.HandleCommand("command script add -o -f %s.cmd_locals goose-locals" % mod)
    debugger.HandleCommand("command script add -o -f %s.cmd_as goose-as" % mod)
    debugger.HandleCommand("command script add -o -f %s.cmd_bt goose-bt" % mod)
    debugger.HandleCommand("command script add -o -f %s.cmd_globals goose-globals" % mod)
    debugger.HandleCommand("command script add -o -f %s.cmd_catch goose-catch" % mod)
    debugger.HandleCommand("command script add -o -f %s.cmd_frame_format goose-frame-format" % mod)
