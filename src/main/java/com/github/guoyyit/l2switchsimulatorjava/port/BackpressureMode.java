package com.github.guoyyit.l2switchsimulatorjava.port;

/**
 * 队列满时的背压策略：丢帧或暂停入端口。
 */
public enum BackpressureMode {
    DROP,
    PAUSE
}
