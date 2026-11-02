use sample_crate::Point;

fn main() {
    let p = Point { x: 1, y: 2 };
    println!("{}", sample_crate::run(&p, 1));
}
