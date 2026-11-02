use sample_crate::Point;

#[test]
fn smoke() {
    let p = Point { x: 1, y: 2 };
    assert_eq!(sample_crate::run(&p, 3), 6);
}
