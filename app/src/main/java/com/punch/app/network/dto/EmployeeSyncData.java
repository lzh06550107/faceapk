package com.punch.app.network.dto;

import com.punch.app.model.Employee;

import java.util.ArrayList;
import java.util.List;

public final class EmployeeSyncData {
    public final List<Employee> employees = new ArrayList<>();
    public final List<String> deletedIds = new ArrayList<>();
    public final List<ChangeItem> changeItems = new ArrayList<>();
    public boolean hasMore;
    public int page;
    public int totalPages;
    public long serverTime;

    public static final class ChangeItem {
        public String numbers = "";
        public String opType = "";
        public long opTime;
        public Employee employee;
    }
}
