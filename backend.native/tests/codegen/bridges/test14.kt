open class A<T, U> {
    open fun foo(x: T, y: U) {
        println(x.toString())
        println(y.toString())
    }
}

interface I1<T> {
    fun foo(x: Int, y: T)
}

interface I2<T> {
    fun foo(x: T, y: Int)
}

class B : A<Int, Int>(), I1<Int>, I2<Int> {
    var z: Int = 5
    var q: Int = 7
    override fun foo(x: Int, y: Int) {
        z = x
        q = y
    }
}

fun zzz(a: A<Int, Int>) {
    a.foo(42, 56)
}

fun main(args: Array<String>) {
    val b = B()
    zzz(b)
    val a = A<Int, Int>()
    zzz(a)
    println(b.z)
    println(b.q)
    val i1: I1<Int> = b
    i1.foo(56, 42)
    println(b.z)
    println(b.q)
    val i2: I2<Int> = b
    i2.foo(156, 142)
    println(b.z)
    println(b.q)
}