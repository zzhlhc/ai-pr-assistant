package com.zhouziheng.review.task;

public enum TaskStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED;

    public boolean isFinished() {
        return this == SUCCESS || this == FAILED;
    }
}
