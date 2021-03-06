/**
 *  Copyright (C) 2010 Cloud.com, Inc.  All rights reserved.
 * 
 * This software is licensed under the GNU General Public License v3 or later.
 * 
 * It is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */
package com.cloud.api.response;

import java.util.Date;

import com.cloud.api.ApiConstants;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class SystemVmResponse extends BaseResponse {
    @SerializedName("id") @Param(description="the ID of the system VM")
    private Long id;

    @SerializedName("systemvmtype") @Param(description="the system VM type")
    private String systemVmType;

    @SerializedName("jobid") @Param(description="the job ID associated with the system VM. This is only displayed if the router listed is part of a currently running asynchronous job.")
    private Long jobId;

    @SerializedName("jobstatus") @Param(description="the job status associated with the system VM.  This is only displayed if the router listed is part of a currently running asynchronous job.")
    private Integer jobStatus;

    @SerializedName("zoneid") @Param(description="the Zone ID for the system VM")
    private Long zoneId;

    @SerializedName("zonename") @Param(description="the Zone name for the system VM")
    private String zoneName;

    @SerializedName("dns1") @Param(description="the first DNS for the system VM")
    private String dns1;

    @SerializedName("dns2") @Param(description="the second DNS for the system VM")
    private String dns2;

    @SerializedName("networkdomain") @Param(description="the network domain for the system VM")
    private String networkDomain;

    @SerializedName("gateway") @Param(description="the gateway for the system VM")
    private String gateway;

    @SerializedName("name") @Param(description="the name of the system VM")
    private String name;

    @SerializedName("podid") @Param(description="the Pod ID for the system VM")
    private Long podId;

    @SerializedName("hostid") @Param(description="the host ID for the system VM")
    private Long hostId;

    @SerializedName("hostname") @Param(description="the hostname for the system VM")
    private String hostName;

    @SerializedName(ApiConstants.PRIVATE_IP) @Param(description="the private IP address for the system VM")
    private String privateIp;

    @SerializedName(ApiConstants.PRIVATE_MAC_ADDRESS) @Param(description="the private MAC address for the system VM")
    private String privateMacAddress;

    @SerializedName(ApiConstants.PRIVATE_NETMASK) @Param(description="the private netmask for the system VM")
    private String privateNetmask;
    
    @SerializedName(ApiConstants.LINK_LOCAL_IP) @Param(description="the link local IP address for the system vm")
    private String linkLocalIp;

    @SerializedName(ApiConstants.LINK_LOCAL_MAC_ADDRESS) @Param(description="the link local MAC address for the system vm")
    private String linkLocalMacAddress;

    @SerializedName(ApiConstants.LINK_LOCAL_MAC_NETMASK) @Param(description="the link local netmask for the system vm")
    private String linkLocalNetmask;

    @SerializedName("publicip") @Param(description="the public IP address for the system VM")
    private String publicIp;

    @SerializedName("publicmacaddress") @Param(description="the public MAC address for the system VM")
    private String publicMacAddress;

    @SerializedName("publicnetmask") @Param(description="the public netmask for the system VM")
    private String publicNetmask;

    @SerializedName("templateid") @Param(description="the template ID for the system VM")
    private Long templateId;

    @SerializedName("created") @Param(description="the date and time the system VM was created")
    private Date created;

    @SerializedName("state") @Param(description="the state of the system VM")
    private String state;
    
    @SerializedName("activeviewersessions") @Param(description="the number of active console sessions for the console proxy system vm")
    private Integer activeViewerSessions;
    
    public Long getObjectId() {
    	return getId();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSystemVmType() {
        return systemVmType;
    }

    public void setSystemVmType(String systemVmType) {
        this.systemVmType = systemVmType;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public Integer getJobStatus() {
        return jobStatus;
    }

    public void setJobStatus(Integer jobStatus) {
        this.jobStatus = jobStatus;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public void setZoneId(Long zoneId) {
        this.zoneId = zoneId;
    }

    public String getZoneName() {
        return zoneName;
    }

    public void setZoneName(String zoneName) {
        this.zoneName = zoneName;
    }

    public String getDns1() {
        return dns1;
    }

    public void setDns1(String dns1) {
        this.dns1 = dns1;
    }

    public String getDns2() {
        return dns2;
    }

    public void setDns2(String dns2) {
        this.dns2 = dns2;
    }

    public String getNetworkDomain() {
        return networkDomain;
    }

    public void setNetworkDomain(String networkDomain) {
        this.networkDomain = networkDomain;
    }

    public String getGateway() {
        return gateway;
    }

    public void setGateway(String gateway) {
        this.gateway = gateway;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getPodId() {
        return podId;
    }

    public void setPodId(Long podId) {
        this.podId = podId;
    }

    public Long getHostId() {
        return hostId;
    }

    public void setHostId(Long hostId) {
        this.hostId = hostId;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    public String getPrivateIp() {
        return privateIp;
    }

    public void setPrivateIp(String privateIp) {
        this.privateIp = privateIp;
    }

    public String getPrivateMacAddress() {
        return privateMacAddress;
    }

    public void setPrivateMacAddress(String privateMacAddress) {
        this.privateMacAddress = privateMacAddress;
    }

    public String getPrivateNetmask() {
        return privateNetmask;
    }

    public void setPrivateNetmask(String privateNetmask) {
        this.privateNetmask = privateNetmask;
    }

    public String getPublicIp() {
        return publicIp;
    }

    public void setPublicIp(String publicIp) {
        this.publicIp = publicIp;
    }

    public String getPublicMacAddress() {
        return publicMacAddress;
    }

    public void setPublicMacAddress(String publicMacAddress) {
        this.publicMacAddress = publicMacAddress;
    }

    public String getPublicNetmask() {
        return publicNetmask;
    }

    public void setPublicNetmask(String publicNetmask) {
        this.publicNetmask = publicNetmask;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Integer getActiveViewerSessions() {
        return activeViewerSessions;
    }

    public void setActiveViewerSessions(Integer activeViewerSessions) {
        this.activeViewerSessions = activeViewerSessions;
    }

    public String getLinkLocalIp() {
        return linkLocalIp;
    }

    public void setLinkLocalIp(String linkLocalIp) {
        this.linkLocalIp = linkLocalIp;
    }

    public String getLinkLocalMacAddress() {
        return linkLocalMacAddress;
    }

    public void setLinkLocalMacAddress(String linkLocalMacAddress) {
        this.linkLocalMacAddress = linkLocalMacAddress;
    }

    public String getLinkLocalNetmask() {
        return linkLocalNetmask;
    }

    public void setLinkLocalNetmask(String linkLocalNetmask) {
        this.linkLocalNetmask = linkLocalNetmask;
    }
}
