/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;

import org.h2.api.ErrorCode;
import org.h2.command.dml.SetTypes;
import org.h2.message.DbException;
import org.h2.security.SHA256;
import org.h2.store.fs.FilePathEncrypt;
import org.h2.store.fs.FilePathRec;
import org.h2.store.fs.FileUtils;
import org.h2.util.New;
import org.h2.util.SortedProperties;
import org.h2.util.StringUtils;
import org.h2.util.Utils;

/**
 * Encapsulates the connection settings, including user name and password.
 */
public class ConnectionInfo implements Cloneable {
    private static final HashSet<String> KNOWN_SETTINGS = New.hashSet();

    private Properties prop = new Properties(); //里面的key都会自动转成大写
    private String originalURL;
    private String url;
    private String user;
    private byte[] filePasswordHash;
    private byte[] fileEncryptionKey;
    private byte[] userPasswordHash;

    /**
     * The database name
     */
    private String name;
    private String nameNormalized;
    private boolean remote;
    private boolean ssl;
    private boolean persistent;
    private boolean unnamed;

    /**
     * Create a connection info object.
     *
     * @param name the database name (including tags), but without the
     *            "jdbc:h2:" prefix
     */
    public ConnectionInfo(String name) {
        this.name = name;
        this.url = Constants.START_URL + name; //如jdbc:h2:mydb，没有tcp了，此时persistent也是true
        parseName();
    }

    /**
     * Create a connection info object.
     *
     * @param u the database URL (must start with jdbc:h2:)
     * @param info the connection properties
     */
    public ConnectionInfo(String u, Properties info) {
        u = remapURL(u);
        this.originalURL = u; //originalURL不会再变
        if (!u.startsWith(Constants.START_URL)) { //"jdbc:h2:"
            throw DbException.getInvalidValueException("url", u);
        }
        this.url = u; //url在接下来的代码中会再变，去掉参数
        readProperties(info);
        readSettingsFromURL();
        setUserName(removeProperty("USER", ""));
        convertPasswords();

		//去掉"jdbc:h2:"，比如jdbc:h2:tcp://localhost:9092/test9
		//name = tcp://localhost:9092/test9
        name = url.substring(Constants.START_URL.length());
        parseName();
        String recoverTest = removeProperty("RECOVER_TEST", null);
        if (recoverTest != null) {
            FilePathRec.register();
            try {
                Utils.callStaticMethod("org.h2.store.RecoverTester.init", recoverTest);
            } catch (Exception e) {
                throw DbException.convert(e);
            }
            name = "rec:" + name;
        }
    }

    static {
        ArrayList<String> list = SetTypes.getTypes();
        HashSet<String> set = KNOWN_SETTINGS;
        set.addAll(list);
        String[] connectionTime = { "ACCESS_MODE_DATA", "AUTOCOMMIT", "CIPHER",
                "CREATE", "CACHE_TYPE", "FILE_LOCK", "IGNORE_UNKNOWN_SETTINGS",
                "IFEXISTS", "INIT", "PASSWORD", "RECOVER", "RECOVER_TEST",
                "USER", "AUTO_SERVER", "AUTO_SERVER_PORT", "NO_UPGRADE",
                "AUTO_RECONNECT", "OPEN_NEW", "PAGE_SIZE", "PASSWORD_HASH", "JMX" };
        for (String key : connectionTime) {
            if (SysProperties.CHECK && set.contains(key)) {
                DbException.throwInternalError(key);
            }
            set.add(key);
        }
    }

    private static boolean isKnownSetting(String s) {
        return KNOWN_SETTINGS.contains(s);
    }

    @Override
    public ConnectionInfo clone() throws CloneNotSupportedException {
        ConnectionInfo clone = (ConnectionInfo) super.clone();
        clone.prop = (Properties) prop.clone();
        clone.filePasswordHash = Utils.cloneByteArray(filePasswordHash);
        clone.fileEncryptionKey = Utils.cloneByteArray(fileEncryptionKey);
        clone.userPasswordHash = Utils.cloneByteArray(userPasswordHash);
        return clone;
    }

    private void parseName() {
        if (".".equals(name)) {
            name = "mem:";
        }
        if (name.startsWith("tcp:")) {
            remote = true;
            name = name.substring("tcp:".length());
        } else if (name.startsWith("ssl:")) {
            remote = true;
            ssl = true;
            name = name.substring("ssl:".length());
        } else if (name.startsWith("mem:")) {
            persistent = false;
            if ("mem:".equals(name)) {
                unnamed = true;
            }
        } else if (name.startsWith("file:")) {
            name = name.substring("file:".length());
            persistent = true;
        } else {
            persistent = true; //等同于"file:name"，在ConnectionInfo(String name)传过来时就是数据库名，没有前缀，在client端是tcp
        }
        if (persistent && !remote) {
            if ("/".equals(SysProperties.FILE_SEPARATOR)) {
                name = name.replace('\\', '/');
            } else {
                name = name.replace('/', '\\');
            }
        }
    }

    /**
     * Set the base directory of persistent databases, unless the database is in
     * the user home folder (~).
     *
     * @param dir the new base directory
     */
    public void setBaseDir(String dir) {
        if (persistent) {
            String absDir = FileUtils.unwrap(FileUtils.toRealPath(dir));
            boolean absolute = FileUtils.isAbsolute(name);
            String n;
            String prefix = null;
            if (dir.endsWith(SysProperties.FILE_SEPARATOR)) {
                dir = dir.substring(0, dir.length() - 1);
            }
            if (absolute) {
                n = name;
            } else {
                n  = FileUtils.unwrap(name);
                prefix = name.substring(0, name.length() - n.length());
                n = dir + SysProperties.FILE_SEPARATOR + n;
            }
            String normalizedName = FileUtils.unwrap(FileUtils.toRealPath(n));
            if (normalizedName.equals(absDir) || !normalizedName.startsWith(absDir)) {
                // database name matches the baseDir or
                // database name is clearly outside of the baseDir
                throw DbException.get(ErrorCode.IO_EXCEPTION_1, normalizedName + " outside " +
                        absDir);
            }
            if (absDir.endsWith("/") || absDir.endsWith("\\")) {
                // no further checks are needed for C:/ and similar
            } else if (normalizedName.charAt(absDir.length()) != '/') {
                // database must be within the directory
                // (with baseDir=/test, the database name must not be
                // /test2/x and not /test2)
                throw DbException.get(ErrorCode.IO_EXCEPTION_1, normalizedName + " outside " +
                        absDir);
            }
            if (!absolute) {
                name = prefix + dir + SysProperties.FILE_SEPARATOR + FileUtils.unwrap(name);
            }
        }
    }

    /**
     * Check if this is a remote connection.
     *
     * @return true if it is
     */
    public boolean isRemote() {
        return remote;
    }

    /**
     * Check if the referenced database is persistent.
     *
     * @return true if it is
     */
    public boolean isPersistent() {
        return persistent;
    }

    /**
     * Check if the referenced database is an unnamed in-memory database.
     *
     * @return true if it is
     */
    boolean isUnnamedInMemory() {
        return unnamed;
    }

    private void readProperties(Properties info) {
        Object[] list = new Object[info.size()];
        info.keySet().toArray(list);
        DbSettings s = null;

		//可在info中配三种参数，相关文档见:E:\H2\my-h2\my-h2-docs\999 可配置的参数汇总.java中的1、2、3项
        for (Object k : list) {
            String key = StringUtils.toUpperEnglish(k.toString());
            if (prop.containsKey(key)) {
                throw DbException.get(ErrorCode.DUPLICATE_PROPERTY_1, key);
            }
            Object value = info.get(k);
			//支持org.h2.command.dml.SetTypes中的参数和ConnectionInfo与connectionTime相关的参数
            if (isKnownSetting(key)) {
                prop.put(key, value);
            } else {
                if (s == null) {
                    s = getDbSettings();
                }
				//org.h2.constant.DbSettings中的参数
                if (s.containsKey(key)) {
                    prop.put(key, value);
                }
            }
        }
    }

    private void readSettingsFromURL() {
		//如url=jdbc:h2:tcp://localhost:9092/test9;optimize_distinct=true;early_filter=true;nested_joins=false
        DbSettings dbSettings = DbSettings.getInstance(null);
        int idx = url.indexOf(';');//用";"号来分隔参数，第一个";"号表明url和参数的分界，之后的";"号用来分隔多个参数
        if (idx >= 0) {
			//optimize_distinct=true;early_filter=true;nested_joins=false
            String settings = url.substring(idx + 1);
			//jdbc:h2:tcp://localhost:9092/test9
            url = url.substring(0, idx);
			//[optimize_distinct=true, early_filter=true, nested_joins=false]
            String[] list = StringUtils.arraySplit(settings, ';', false);
            for (String setting : list) {
                if (setting.length() == 0) {
                    continue;
                }
                int equal = setting.indexOf('=');
                if (equal < 0) {
                    throw getFormatException();
                }
                String value = setting.substring(equal + 1);
                String key = setting.substring(0, equal);
                key = StringUtils.toUpperEnglish(key);
				//info中除了可以配三种参数外(相关文档见:E:\H2\my-h2\my-h2-docs\999 可配置的参数汇总.java中的1、2、3项)
				//还可以配其他参数，但是被忽略
				//但是url中只能配三种参数
                if (!isKnownSetting(key) && !dbSettings.containsKey(key)) {
                    throw DbException.get(ErrorCode.UNSUPPORTED_SETTING_1, key);
                }
				//不能与info中的参数重复(如果值相同就不会报错)
				//例子见my.test.ConnectionInfoTest
                String old = prop.getProperty(key);
                if (old != null && !old.equals(value)) {
                    throw DbException.get(ErrorCode.DUPLICATE_PROPERTY_1, key);
                }
                prop.setProperty(key, value);
            }
        }
    }

    private char[] removePassword() {
        Object p = prop.remove("PASSWORD");
        if (p == null) {
            return new char[0];
        } else if (p instanceof char[]) {
			//例如:
			//Properties prop = new Properties();
			//prop.put("password", new char[]{});
			//因为Properties继承了java.util.Hashtable<K, V>
			//可以调用java.util.Hashtable.put(Object, Object)
            return (char[]) p;
        } else {
            return p.toString().toCharArray();
        }
    }

    /**
     * Split the password property into file password and user password if
     * necessary, and convert them to the internal hash format.
     */
    private void convertPasswords() {
        char[] password = removePassword();
        boolean passwordHash = removeProperty("PASSWORD_HASH", false); //如果PASSWORD_HASH参数是true那么不再进行SHA256

		//如果配置了CIPHER，则password包含两部份，用一个空格分开这两部份，第一部份是filePassword，第二部份是userPassword。
		//如果PASSWORD_HASH参数是true那么不再进行SHA256，此时必须使用16进制字符，字符个数是偶数。
        //如果PASSWORD_HASH参数是false，不必是16进制字符，会按SHA256算法进行hash
        if (getProperty("CIPHER", null) != null) {
            // split password into (filePassword+' '+userPassword)
            int space = -1;
            for (int i = 0, len = password.length; i < len; i++) {
                if (password[i] == ' ') {
                    space = i;
                    break;
                }
            }
            if (space < 0) {
                throw DbException.get(ErrorCode.WRONG_PASSWORD_FORMAT);
            }
            char[] np = new char[password.length - space - 1];
            char[] filePassword = new char[space];
            System.arraycopy(password, space + 1, np, 0, np.length);
            System.arraycopy(password, 0, filePassword, 0, space);
            Arrays.fill(password, (char) 0);
            password = np;
			//filePasswordHash用"file"进行hash
            fileEncryptionKey = FilePathEncrypt.getPasswordBytes(filePassword);
            filePasswordHash = hashPassword(passwordHash, "file", filePassword);
        }
		//userPasswordHash用用户名进行hash
        userPasswordHash = hashPassword(passwordHash, user, password);
    }

    private static byte[] hashPassword(boolean passwordHash, String userName, char[] password) {
		//如果PASSWORD_HASH参数是true那么不再进行SHA256vn
        if (passwordHash) {
            return StringUtils.convertHexToBytes(new String(password));
        }
        if (userName.length() == 0 && password.length == 0) {
            return new byte[0];
        }
        //会生成32个字节，32*8刚好是256 bit，刚好对应SHA256的名字
        return SHA256.getKeyPasswordHash(userName, password);
    }

    /**
     * Get a boolean property if it is set and return the value.
     *
     * @param key the property name
     * @param defaultValue the default value
     * @return the value
     */
    boolean getProperty(String key, boolean defaultValue) {
        String x = getProperty(key, null);
        if (x == null) {
            return defaultValue;
        }
        // support 0 / 1 (like the parser)
        if (x.length() == 1 && Character.isDigit(x.charAt(0))) {
            return Integer.parseInt(x) != 0;
        }
        return Boolean.parseBoolean(x);
    }

    /**
     * Remove a boolean property if it is set and return the value.
     *
     * @param key the property name
     * @param defaultValue the default value
     * @return the value
     */
    public boolean removeProperty(String key, boolean defaultValue) {
        String x = removeProperty(key, null);
        return x == null ? defaultValue : Boolean.parseBoolean(x);
    }

    /**
     * Remove a String property if it is set and return the value.
     *
     * @param key the property name
     * @param defaultValue the default value
     * @return the value
     */
    String removeProperty(String key, String defaultValue) {
        if (SysProperties.CHECK && !isKnownSetting(key)) {
            DbException.throwInternalError(key);
        }
        Object x = prop.remove(key);
        return x == null ? defaultValue : x.toString();
    }

    /**
     * Get the unique and normalized database name (excluding settings).
     *
     * @return the database name
     */
    public String getName() {
        if (persistent) {
            if (nameNormalized == null) {
                if (!SysProperties.IMPLICIT_RELATIVE_PATH) {
                    if (!FileUtils.isAbsolute(name)) {
                        if (name.indexOf("./") < 0 &&
                                name.indexOf(".\\") < 0 &&
                                name.indexOf(":/") < 0 &&
                                name.indexOf(":\\") < 0) {
                            // the name could start with "./", or
                            // it could start with a prefix such as "nio:./"
                            // for Windows, the path "\test" is not considered
                            // absolute as the drive letter is missing,
                            // but we consider it absolute
                            throw DbException.get(
                                    ErrorCode.URL_RELATIVE_TO_CWD,
                                    originalURL);
                        }
                    }
                }
                String suffix = Constants.SUFFIX_PAGE_FILE;
                String n;
                if (FileUtils.exists(name + suffix)) {
                    n = FileUtils.toRealPath(name + suffix);
                } else {
                    suffix = Constants.SUFFIX_MV_FILE;
                    n = FileUtils.toRealPath(name + suffix);
                }
                String fileName = FileUtils.getName(n);
                if (fileName.length() < suffix.length() + 1) {
                    throw DbException.get(ErrorCode.INVALID_DATABASE_NAME_1, name);
                }
                nameNormalized = n.substring(0, n.length() - suffix.length());
            }
            return nameNormalized;
        }
        return name;
    }

    /**
     * Get the file password hash if it is set.
     *
     * @return the password hash or null
     */
    public byte[] getFilePasswordHash() {
        return filePasswordHash;
    }

    byte[] getFileEncryptionKey() {
        return fileEncryptionKey;
    }

    /**
     * Get the name of the user.
     *
     * @return the user name
     */
    public String getUserName() {
        return user;
    }

    /**
     * Get the user password hash.
     *
     * @return the password hash
     */
    byte[] getUserPasswordHash() {
        return userPasswordHash;
    }

    /**
     * Get the property keys.
     *
     * @return the property keys
     */
    String[] getKeys() {
        String[] keys = new String[prop.size()];
        prop.keySet().toArray(keys);
        return keys;
    }

    /**
     * Get the value of the given property.
     *
     * @param key the property key
     * @return the value as a String
     */
    String getProperty(String key) {
        Object value = prop.get(key);
        if (value == null || !(value instanceof String)) {
            return null;
        }
        return value.toString();
    }

    /**
     * Get the value of the given property.
     *
     * @param key the property key
     * @param defaultValue the default value
     * @return the value as a String
     */
    int getProperty(String key, int defaultValue) {
        if (SysProperties.CHECK && !isKnownSetting(key)) {
            DbException.throwInternalError(key);
        }
        String s = getProperty(key);
        return s == null ? defaultValue : Integer.parseInt(s);
    }

    /**
     * Get the value of the given property.
     *
     * @param key the property key
     * @param defaultValue the default value
     * @return the value as a String
     */
    public String getProperty(String key, String defaultValue) {
        if (SysProperties.CHECK && !isKnownSetting(key)) {
            DbException.throwInternalError(key);
        }
        String s = getProperty(key);
        return s == null ? defaultValue : s;
    }

    /**
     * Get the value of the given property.
     *
     * @param setting the setting id
     * @param defaultValue the default value
     * @return the value as a String
     */
    String getProperty(int setting, String defaultValue) {
        String key = SetTypes.getTypeName(setting);
        String s = getProperty(key);
        return s == null ? defaultValue : s;
    }

    /**
     * Get the value of the given property.
     *
     * @param setting the setting id
     * @param defaultValue the default value
     * @return the value as an integer
     */
    int getIntProperty(int setting, int defaultValue) {
        String key = SetTypes.getTypeName(setting);
        String s = getProperty(key, null);
        try {
            return s == null ? defaultValue : Integer.decode(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Check if this is a remote connection with SSL enabled.
     *
     * @return true if it is
     */
    boolean isSSL() {
        return ssl;
    }

    /**
     * Overwrite the user name. The user name is case-insensitive and stored in
     * uppercase. English conversion is used.
     *
     * @param name the user name
     */
    public void setUserName(String name) {
        this.user = StringUtils.toUpperEnglish(name);
    }

    /**
     * Set the user password hash.
     *
     * @param hash the new hash value
     */
    public void setUserPasswordHash(byte[] hash) {
        this.userPasswordHash = hash;
    }

    /**
     * Set the file password hash.
     *
     * @param hash the new hash value
     */
    public void setFilePasswordHash(byte[] hash) {
        this.filePasswordHash = hash;
    }

    public void setFileEncryptionKey(byte[] key) {
        this.fileEncryptionKey = key;
    }

    /**
     * Overwrite a property.
     *
     * @param key the property name
     * @param value the value
     */
    public void setProperty(String key, String value) {
        // value is null if the value is an object
        if (value != null) {
            prop.setProperty(key, value);
        }
    }

    /**
     * Get the database URL.
     *
     * @return the URL
     */
    public String getURL() {
        return url;
    }

    /**
     * Get the complete original database URL.
     *
     * @return the database URL
     */
    public String getOriginalURL() {
        return originalURL;
    }

    /**
     * Set the original database URL.
     *
     * @param url the database url
     */
    public void setOriginalURL(String url) {
        originalURL = url;
    }

    /**
     * Generate an URL format exception.
     *
     * @return the exception
     */
    DbException getFormatException() {
        String format = Constants.URL_FORMAT;
        return DbException.get(ErrorCode.URL_FORMAT_ERROR_2, format, url);
    }

    /**
     * Switch to server mode, and set the server name and database key.
     *
     * @param serverKey the server name, '/', and the security key
     */
    public void setServerKey(String serverKey) {
        remote = true;
        persistent = false;
        this.name = serverKey;
    }

    public DbSettings getDbSettings() {
        DbSettings defaultSettings = DbSettings.getDefaultSettings();
        HashMap<String, String> s = New.hashMap();
        for (Object k : prop.keySet()) {
            String key = k.toString();
            if (!isKnownSetting(key) && defaultSettings.containsKey(key)) {
                s.put(key, prop.getProperty(key));
            }
        }
        return DbSettings.getInstance(s);
    }

    private static String remapURL(String url) {
		//比如System.setProperty("h2.urlMap", "E:/H2/my-h2/my-h2-src/my/test/h2.urlMap.properties");
		//假设url="my.url"，那么可以在h2.urlMap.properties中重新映射: my.url=my.url=jdbc:h2:tcp://localhost:9092/test9
		//最后返回的url实际是jdbc:h2:tcp://localhost:9092/test9
        String urlMap = SysProperties.URL_MAP;
        if (urlMap != null && urlMap.length() > 0) {
            try {
                SortedProperties prop;
                prop = SortedProperties.loadProperties(urlMap);
                String url2 = prop.getProperty(url);
                if (url2 == null) {
                    prop.put(url, "");
                    prop.store(urlMap);
                } else {
                    url2 = url2.trim();
                    if (url2.length() > 0) {
                        return url2;
                    }
                }
            } catch (IOException e) {
                throw DbException.convert(e);
            }
        }
        return url;
    }

}
