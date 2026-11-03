package com.abdatytch.interface_service.dto;

/**
 * DTO pour les informations de géolocalisation et de tentative de connexion
 */
public class GeoLocationInfo {
    private String id;
    private String createdAt;
    private String updatedAt;
    private Integer asn;
    private String browserFamily;
    private String browserVersion;
    private String city;
    private String country;
    private String countryCode;
    private String deviceFamily;
    private String deviceInfo;
    private String deviceType;
    private String errorMessage;
    private String eventType;
    private String ipAddress;
    private String isp;
    private Double latitude;
    private String location;
    private Double longitude;
    private String organization;
    private String osFamily;
    private String osVersion;
    private String phoneNumber;
    private String region;
    private String status;
    private String timestamp;
    private String timezone;

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    public Integer getAsn() { return asn; }
    public void setAsn(Integer asn) { this.asn = asn; }
    public String getBrowserFamily() { return browserFamily; }
    public void setBrowserFamily(String browserFamily) { this.browserFamily = browserFamily; }
    public String getBrowserVersion() { return browserVersion; }
    public void setBrowserVersion(String browserVersion) { this.browserVersion = browserVersion; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
    public String getDeviceFamily() { return deviceFamily; }
    public void setDeviceFamily(String deviceFamily) { this.deviceFamily = deviceFamily; }
    public String getDeviceInfo() { return deviceInfo; }
    public void setDeviceInfo(String deviceInfo) { this.deviceInfo = deviceInfo; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getIsp() { return isp; }
    public void setIsp(String isp) { this.isp = isp; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public String getOrganization() { return organization; }
    public void setOrganization(String organization) { this.organization = organization; }
    public String getOsFamily() { return osFamily; }
    public void setOsFamily(String osFamily) { this.osFamily = osFamily; }
    public String getOsVersion() { return osVersion; }
    public void setOsVersion(String osVersion) { this.osVersion = osVersion; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
}
