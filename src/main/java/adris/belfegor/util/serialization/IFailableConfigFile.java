package adris.belfegor.util.serialization;

public interface IFailableConfigFile {
    void onFailLoad();

    boolean failedToLoad();
}
