package com.punch.app.model;


public class Employee {
    
    public String id;
    
    public String name;
    
    public String dept;
    
    public String faceImageUrl;
    
    public String faceImageSha256;
    
    public int faceVersion;
    
    public String faceStatus;
    
    public String localFaceId;
    
    public int faceRegistered;
    
    public String assignedLineCode;
    
    public String assignedLineName;
    
    public String status;
    
    public int syncVersion;
    
    public int isDeleted;
    
    public long updatedAt;
    
    public transient int faceIntId = -1;
}
