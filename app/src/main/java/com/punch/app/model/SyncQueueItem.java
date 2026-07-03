package com.punch.app.model;


public class SyncQueueItem {
    
    public int id;
    
    public String recordId;
    
    public String action;
    
    public int retryCount;
    
    public long createdAt;
    
    public long lastRetry;
}
