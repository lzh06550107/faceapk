package com.punch.app.network.dto;

public final class EventResultDto {
    private EventResultDto() {
    }

    public static final class EmployeeResult {
        public String numbers = "";
        public String opType = "";
        public boolean success;
        public String failMsg = "";

        public static EmployeeResult success(String numbers, String opType) {
            EmployeeResult result = new EmployeeResult();
            result.numbers = numbers == null ? "" : numbers;
            result.opType = opType == null ? "" : opType;
            result.success = true;
            return result;
        }

        public static EmployeeResult failure(String numbers, String opType, String failMsg) {
            EmployeeResult result = new EmployeeResult();
            result.numbers = numbers == null ? "" : numbers;
            result.opType = opType == null ? "" : opType;
            result.success = false;
            result.failMsg = failMsg == null ? "" : failMsg;
            return result;
        }
    }
}
