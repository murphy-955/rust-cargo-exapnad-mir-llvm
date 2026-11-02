use sample_crate::Point;

fn main() {
    let p = Point { x: 1, y: 2 };
    println!("{}", sample_crate::run(&p, 3));
}

// 例 1：常量传播 + 死代码消除 —— release 下整个函数折叠成一条 mov
pub fn const_fold() -> i32 {
    let v = vec![1, 2, 3, 4];   // dev: 调 malloc + 4 次写入；release: 全部消失
    v.iter().sum()              // release: 直接返回 10
}

// 例 2：越界检查消除 + 向量化 —— 同一个函数，dev/release 汇编量级差
pub fn sum(v: &[i32]) -> i32 {
    let mut s = 0;
    for i in 0..v.len() {
        s += v[i];   // dev: 每次循环都跑 bounds check，越界则调 panic
    }
    s
}

// 例 3：迭代器链扁平化 —— dev 里层层嵌套调用，release 合成单循环
pub fn pipeline(v: &[i32]) -> i32 {
    v.iter()
        .filter(|x| **x > 0)
        .map(|x| x * 2)
        .sum()
}

// 例 4：已知分支消除 —— release 里 is_empty 和 v[0] 的检查合并/删除
pub fn first_or_zero(v: &[i32]) -> i32 {
    if v.is_empty() { 0 } else { v[0] }
}
