package com.digixmed.cloud.icu.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NurseInfo {
    /** nurse ID, default "041660" (陈琳) */
    private String nurseId;
    /** resolved trueName from account, or empty if not found */
    private String nurseName;
    /** resolution path: "param_Yishi" | "source_record" | "not_found" */
    private String source;
    /** null on success; "NURSE_NOT_FOUND" when lookup fails */
    private String reasonCode;
}
