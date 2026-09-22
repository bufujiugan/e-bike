package com.bike.server.aftersales;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix="bike.after-sales.mail")
public class AfterSalesMailConfig {
    private boolean enabled=false;
    public boolean getEnabled(){return enabled;}
    public void setEnabled(boolean value){enabled=value;}
    private String host="";
    public String getHost(){return host;}
    public void setHost(String value){host=value;}
    private int port=587;
    public int getPort(){return port;}
    public void setPort(int value){port=value;}
    private String username="";
    public String getUsername(){return username;}
    public void setUsername(String value){username=value;}
    private String password="";
    public String getPassword(){return password;}
    public void setPassword(String value){password=value;}
    private String from="";
    public String getFrom(){return from;}
    public void setFrom(String value){from=value;}
    private String staffRecipient="";
    public String getStaffRecipient(){return staffRecipient;}
    public void setStaffRecipient(String value){staffRecipient=value;}
    private boolean startTls=true;
    public boolean getStartTls(){return startTls;}
    public void setStartTls(boolean value){startTls=value;}
    private boolean ssl=false;
    public boolean getSsl(){return ssl;}
    public void setSsl(boolean value){ssl=value;}
}
