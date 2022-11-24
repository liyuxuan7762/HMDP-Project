package com.hmdp.utils;

/**
 * 利用Redis实现分布式锁的接口
 */
public interface ILock {
    boolean tryLock(Long timeout);
    void unlock();
}
