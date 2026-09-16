package com.tadpole.instrument.audio

import kotlin.math.*

/** Fixed-frequency RBJ shelf for output compensation, never driven by mouth position. */
class ShelfFilter(private val sampleRate: Int,private val frequency: Float,private val high: Boolean) {
    private var b0=1.0;private var b1=0.0;private var b2=0.0;private var a1=0.0;private var a2=0.0
    private var d0=0.0;private var d1=0.0;private var d2=0.0;private var e1=0.0;private var e2=0.0
    private var z1=0.0;private var z2=0.0;private var remaining=0
    fun setGain(db: Float,frames: Int=0) {
        val gain=if(db.isFinite()) db.coerceIn(-6f,9f) else 0f
        val a=10.0.pow(gain/40.0)
        val w=2*PI*frequency.coerceAtMost(sampleRate*.35f)/sampleRate
        val c=cos(w);val beta=sqrt(2*a)*sin(w)
        val n0:Double;val n1:Double;val n2:Double;val den:Double;val p1:Double;val p2:Double
        if(high) {
            n0=a*((a+1)+(a-1)*c+beta);n1=-2*a*((a-1)+(a+1)*c);n2=a*((a+1)+(a-1)*c-beta)
            den=(a+1)-(a-1)*c+beta;p1=2*((a-1)-(a+1)*c);p2=(a+1)-(a-1)*c-beta
        } else {
            n0=a*((a+1)-(a-1)*c+beta);n1=2*a*((a-1)-(a+1)*c);n2=a*((a+1)-(a-1)*c-beta)
            den=(a+1)+(a-1)*c+beta;p1=-2*((a-1)+(a+1)*c);p2=(a+1)+(a-1)*c-beta
        }
        if(frames<=0) {b0=n0/den;b1=n1/den;b2=n2/den;a1=p1/den;a2=p2/den;remaining=0}
        else {
            d0=(n0/den-b0)/frames;d1=(n1/den-b1)/frames;d2=(n2/den-b2)/frames
            e1=(p1/den-a1)/frames;e2=(p2/den-a2)/frames;remaining=frames
        }
    }
    fun process(x: Float): Float {
        if(remaining>0) {b0+=d0;b1+=d1;b2+=d2;a1+=e1;a2+=e2;remaining--}
        val y=b0*x+z1
        z1=b1*x-a1*y+z2;z2=b2*x-a2*y
        if(!y.isFinite()) {z1=0.0;z2=0.0;return 0f}
        if(abs(z1)<1e-20)z1=0.0
        if(abs(z2)<1e-20)z2=0.0
        return y.toFloat()
    }
}
