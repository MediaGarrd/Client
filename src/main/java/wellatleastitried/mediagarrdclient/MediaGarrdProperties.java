package wellatleastitried.mediagarrdclient;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mediagarrd.client")
public class MediaGarrdProperties {

    private String stateFile = "/var/lib/mediagarrd/client/state.json";
    private String downloadDirectory = "/var/lib/mediagarrd/downloads";
    private Duration pickupInterval = Duration.ofHours(12);
    private int serverPort = 38471;
    private String preferredServerIp = "";
    private String updateRepository = "MediaGarrd/Client";
    private String githubApiBaseUrl = "https://api.github.com";
    private Duration updateCheckInterval = Duration.ofHours(24);
    private Duration backupRunTimeout = Duration.ofHours(6);
    private String serverCa = "";
    private String password = "";
    private boolean resetPassword;

    public String getStateFile() {
        return stateFile;
    }

    public void setStateFile(String stateFile) {
        this.stateFile = stateFile;
    }

    public String getDownloadDirectory() {
        return downloadDirectory;
    }

    public void setDownloadDirectory(String downloadDirectory) {
        this.downloadDirectory = downloadDirectory;
    }

    public Duration getPickupInterval() {
        return pickupInterval;
    }

    public void setPickupInterval(Duration pickupInterval) {
        this.pickupInterval = pickupInterval;
    }

    public int getServerPort() {
        return serverPort;
    }

    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }

    public String getPreferredServerIp() {
        return preferredServerIp;
    }

    public void setPreferredServerIp(String preferredServerIp) {
        this.preferredServerIp = preferredServerIp;
    }

    public String getUpdateRepository() {
        return updateRepository;
    }

    public void setUpdateRepository(String updateRepository) {
        this.updateRepository = updateRepository;
    }

    public String getGithubApiBaseUrl() {
        return githubApiBaseUrl;
    }

    public void setGithubApiBaseUrl(String githubApiBaseUrl) {
        this.githubApiBaseUrl = githubApiBaseUrl;
    }

    public Duration getUpdateCheckInterval() {
        return updateCheckInterval;
    }

    public void setUpdateCheckInterval(Duration updateCheckInterval) {
        this.updateCheckInterval = updateCheckInterval;
    }

    public Duration getBackupRunTimeout() {
        return backupRunTimeout;
    }

    public void setBackupRunTimeout(Duration backupRunTimeout) {
        this.backupRunTimeout = backupRunTimeout;
    }

    public String getServerCa() {
        return serverCa;
    }

    public void setServerCa(String serverCa) {
        this.serverCa = serverCa;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isResetPassword() {
        return resetPassword;
    }

    public void setResetPassword(boolean resetPassword) {
        this.resetPassword = resetPassword;
    }
}
