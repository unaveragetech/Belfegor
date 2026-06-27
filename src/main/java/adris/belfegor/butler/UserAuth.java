package adris.belfegor.butler;

import adris.belfegor.Belfegor;
import adris.belfegor.util.helpers.ConfigHelper;

public class UserAuth {
    private static final String BLACKLIST_PATH = "belfegor_butler_blacklist.txt";
    private static final String WHITELIST_PATH = "belfegor_butler_whitelist.txt";
    private final Belfegor _mod;
    private UserListFile _blacklist = new UserListFile();
    private UserListFile _whitelist = new UserListFile();

    public UserAuth(Belfegor mod) {
        _mod = mod;

        ConfigHelper.ensureCommentedListFileExists(BLACKLIST_PATH, """
                Add butler blacklisted players here.
                Make sure useButlerBlacklist is set to true in the settings file.
                Anything after a pound sign (#) will be ignored.""");
        ConfigHelper.ensureCommentedListFileExists(WHITELIST_PATH, """
                Add butler whitelisted players here.
                Make sure useButlerWhitelist is set to true in the settings file.
                Anything after a pound sign (#) will be ignored.""");

        UserListFile.load(BLACKLIST_PATH, newList -> _blacklist = newList);
        UserListFile.load(WHITELIST_PATH, newList -> _whitelist = newList);
    }

    public boolean isUserAuthorized(String username) {

        // Blacklist gets first priority.
        if (ButlerConfig.getInstance().useButlerBlacklist && _blacklist.containsUser(username)) {
            return false;
        }
        if (ButlerConfig.getInstance().useButlerWhitelist) {
            return _whitelist.containsUser(username);
        }

        // By default accept everyone.
        return true;
    }

}
