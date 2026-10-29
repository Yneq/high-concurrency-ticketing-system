package com.ticketing.finalphase;
/** Cache admission contract; database correctness must not depend on its availability. */
public interface AdmissionGate {
    long acquire(long event,String key);
    void release(long event,String key);
    void project(long event,int remaining);
}
