#![feature(prelude_import)]
extern crate std;
#[prelude_import]
use std::prelude::rust_2021::*;
use std::fmt::Display;

pub struct Point {
    pub x: u64,
    pub y: u64,
}
#[automatically_derived]
impl ::core::fmt::Debug for Point {
    #[inline]
    fn fmt(&self, f: &mut ::core::fmt::Formatter) -> ::core::fmt::Result {
        ::core::fmt::Formatter::debug_struct_field2_finish(f, "Point", "x",
            &self.x, "y", &&self.y)
    }
}
#[automatically_derived]
impl ::core::clone::Clone for Point {
    #[inline]
    fn clone(&self) -> Point {
        Point {
            x: ::core::clone::Clone::clone(&self.x),
            y: ::core::clone::Clone::clone(&self.y),
        }
    }
}

impl Point {
    pub fn get(&self) -> u64 { self.x + self.y }
}

impl Display for Point {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {







        f.write_fmt(format_args!("({0}, {1})", self.x, self.y))
    }
}
pub trait Area {
    fn area(&self)
    -> u64;
}
impl Area for Point {
    fn area(&self) -> u64 { self.x * self.y }
}
pub fn run(p: &Point, n: u64) -> u64 {
    let f = |v: u64| -> u64 { v + n };
    f(p.get())
}
pub mod nested {
    pub fn helper(v: u64) -> u64 { v * 2 }
}
pub fn generic<T: Copy + std::ops::Add<Output = T>>(a: T, b: T) -> T { a + b }
macro_rules! twice { ($e:expr) => { $e + $e }; }
pub fn use_macro(v: u64) -> u64 { v + v }
