use std::fmt::Display;

#[derive(Debug, Clone)]
pub struct Point {
    pub x: u64,
    pub y: u64,
}

impl Point {
    pub fn get(&self) -> u64 {
        self.x + self.y
    }
}

impl Display for Point {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "({}, {})", self.x, self.y)
    }
}

pub trait Area {
    fn area(&self) -> u64;
}

impl Area for Point {
    fn area(&self) -> u64 {
        self.x * self.y
    }
}

pub fn run(p: &Point, n: u64) -> u64 {
    let f = |v: u64| -> u64 { v + n };
    f(p.get())
}

// Exported probe kept by every profile so MIR and assembly can be compared directly.
#[inline(never)]
#[no_mangle]
pub extern "C" fn profile_probe(seed: u64) -> u64 {
    let mut acc = seed;
    for i in 0..64u64 {
        acc = acc.wrapping_mul(6364136223846793005).wrapping_add(i);
    }
    std::hint::black_box(acc)
}

pub mod nested {
    pub fn helper(v: u64) -> u64 {
        v * 2
    }
}

pub fn generic<T: Copy + std::ops::Add<Output = T>>(a: T, b: T) -> T {
    a + b
}

macro_rules! twice {
    ($e:expr) => {
        $e + $e
    };
}

pub fn use_macro(v: u64) -> u64 {
    twice!(v)
}
