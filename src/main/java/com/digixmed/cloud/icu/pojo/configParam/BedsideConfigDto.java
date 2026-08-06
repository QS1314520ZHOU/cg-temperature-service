package com.digixmed.cloud.icu.pojo.configParam;

import com.digixmed.cloud.icu.pojo.commonParam.Group;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class BedsideConfigDto
        implements Serializable {
    private String id;
    private String pid;
    private String groupName;

    public void setId(String id) {
        /* 16 */
        this.id = id;
    }

    public void setPid(String pid) {
        this.pid = pid;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public void setGroups(List<Group> groups) {
        this.groups = groups;
    }

    public void setDeptCode(String deptCode) {
        this.deptCode = deptCode;
    }

    protected boolean canEqual(Object other) {
        return other instanceof BedsideConfigDto;
    }



    public String toString() {
        return "BedsideConfigDto(id=" + getId() + ", pid=" + getPid() + ", groupName=" + getGroupName() + ", groups=" + getGroups() + ", deptCode=" + getDeptCode() + ")";
    }

    /* 18 */
    public String getId() {
        return this.id;
    }

    /* 19 */
    public String getPid() {
        return this.pid;
    }

    public String getGroupName() {
        /* 20 */
        return this.groupName;
        /* 21 */
    }

    private List<Group> groups = new ArrayList<>();
    private String deptCode;

    public List<Group> getGroups() {
        return this.groups;
    }

    public String getDeptCode() {
        /* 22 */
        return this.deptCode;
    }

    public BedsideConfigDto(String groupName) {
        /* 25 */
        this.groupName = groupName;
    }

    public BedsideConfigDto() {
    }
}
