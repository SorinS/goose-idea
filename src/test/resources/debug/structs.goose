// Structs, C-like enums, fixed-mode and variable-mode ADTs.
struct Point { x: i32, y: i32 }
struct Unit { kind: u8, hp: i32 = 100, pos: Point, tag: u8[..8] }
enum Color { Red, Green, Blue }
enum Shape { Circle { r: f64 }, Rect { w: f64, h: f64 }, Dot }
enum Mark { Label { text: u8[varint] }, Pin { at: Point } }

fn area(s: Shape) -> f64 {
    match s { Circle c => 3.0 * c.r * c.r, Rect r => r.w * r.h, Dot => 0.0 }
}

fn main() {
    let p = Point { x: 3, y: -4 };
    var u = Unit { kind: 1, pos: p, tag: "hero" };
    let c = Color.Green;
    let circle = Shape.Circle { r: 1.5 };
    let rect = Shape.Rect { w: 2.0, h: 3.0 };
    let dot = Shape.Dot;
    let label: Mark.. = Mark.Label { text: "hi" };
    var shapes: Shape[>..] = [];
    shapes.push(circle);
    shapes.push(rect);
    u.hp -= 1;
    print(p, " ", u, " ", c, " ", area(circle) + area(rect) + area(dot), " ", label, " ", shapes); // BREAK lldb: frame variable u_g.pos_g.x_g; test-children circle_g; test-children rect_g; test-children dot_g; test-children label_g; test-children shapes_g
}
