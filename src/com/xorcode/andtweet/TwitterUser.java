/**
 * Copyright (C) 2010 yvolk (Yuri Volkov), http://yurivolkov.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xorcode.andtweet;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import java.net.SocketTimeoutException;
import java.util.regex.Pattern;
import java.util.Vector;

import com.xorcode.andtweet.net.Connection;
import com.xorcode.andtweet.net.ConnectionAuthenticationException;
import com.xorcode.andtweet.net.ConnectionCredentialsOfOtherUserException;
import com.xorcode.andtweet.net.ConnectionException;
import com.xorcode.andtweet.net.ConnectionUnavailableException;
import com.xorcode.andtweet.util.SharedPreferencesUtil;

import org.json.JSONObject;

/**
 * The object holds Twitter User's specific information including connection
 * TODO: Implement different data (tweets and their counters...) for different
 * Users.
 * 
 * @author Yuri Volkov
 */
public class TwitterUser {
    private static final String TAG = TwitterUser.class.getSimpleName();

    private Context mContext;

    /**
     * This is same name that is used in Twitter login
     */
    private String mUsername = "";

    /**
     * Was this user _ever_ authenticated?
     */
    private boolean mWasAuthenticated = false;

    /**
     * Was this user authenticated last time credentials were verified?
     * CredentialsVerified.NEVER - after changes of password/OAuth...
     */
    private CredentialsVerified mCredentialsVerified = CredentialsVerified.NEVER;

    private String mPrefsFileName = "";

    /**
     * These preferences are per User
     */
    protected SharedPreferences mSp;

    /**
     * Is this user authenticated with OAuth?
     */
    private boolean mOAuth = false;

    private String mPassword = "";

    private Connection mConnection;

    public enum CredentialsVerified {
        NEVER, FAILED, SUCCEEDED;

        /*
         * Methods to persist in SharedPreferences
         */
        private static final String KEY = "credentials_verified";

        public static CredentialsVerified load(SharedPreferences sp) {
            int ind = sp.getInt(KEY, NEVER.ordinal());
            CredentialsVerified cv = CredentialsVerified.values()[ind];
            return cv;
        }

        public void save(SharedPreferences sp) {
            synchronized (sp) {
                SharedPreferences.Editor editor = sp.edit();
                put(editor);
                editor.commit();
            }
        }

        public void put(SharedPreferences.Editor editor) {
            editor.putInt(KEY, ordinal());
        }
    }

    public CredentialsVerified getCredentialsVerified() {
        return mCredentialsVerified;
    }

    /**
     * Do we have enough credentials to verify them?
     * 
     * @return true == yes
     */
    public boolean getCredentialsPresent() {
        return getConnection().getCredentialsPresent();
    }

    public String getPassword() {
        return mPassword;
    }

    public void setCredentialsVerified(CredentialsVerified cv) {
        mCredentialsVerified = cv;
        mCredentialsVerified.save(mSp);
    }

    /**
     * Get user instance based on global twitter_username
     * 
     * @param Context
     * @param copyGlobal globally stored User preferences are used, including
     *            username, oauth, password
     * @return TwitterUser
     */
    public static TwitterUser getTwitterUser(Context context, boolean copyGlobal) {
        return getTwitterUser(context, null, copyGlobal);
    }

    /**
     * Get (stored) user instance based on explicitly provided username. Global
     * SharedPreferences will be updated.
     * 
     * @param Context
     * @param username in Twitter
     * @return TwitterUser
     */
    public static TwitterUser getTwitterUser(Context context, String username) {
        return getTwitterUser(context, username, false);
    }

    // Array of TwitterUser objects
    private static Vector<TwitterUser> mTu = new Vector<TwitterUser>();

    /**
     * Factory of TwitterUser-s
     * 
     * @param Context
     * @param username in Twitter
     * @param copyGlobal globally stored User preferences are used, including
     *            Username, OAuth, password
     * @return TwitterUser
     */
    private static TwitterUser getTwitterUser(Context context, String username, boolean copyGlobal) {
        // Find TwitterUser object for this user
        boolean found = false;
        int ind = -1;
        int indTemp = -1;
        TwitterUser tu = null;

        username = fixUsername(username);
        if (copyGlobal || (username.length() == 0)) {
            SharedPreferences dsp = PreferenceManager.getDefaultSharedPreferences(context);
            username = fixUsername(dsp.getString(PreferencesActivity.KEY_TWITTER_USERNAME, ""));
        }
        for (ind = 0; ind < mTu.size(); ind++) {
            if (mTu.elementAt(ind).getUsername().compareTo(username) == 0) {
                found = true;
                break;
            }
            if (!mTu.elementAt(ind).wasAuthenticated()) {
                indTemp = ind;
            }
        }
        if (!found) {
            if (!SharedPreferencesUtil.exists(context, SharedPreferencesUtil
                    .prefsFileNameForUser(username))
                    && indTemp >= 0) {
                // This is new User, so
                // Let's reuse existing temp file and forget previous User who
                // wasn't ever authenticated
                ind = indTemp;
                mTu.elementAt(ind).setUsername(username, true);
                found = true;
            }
        }
        if (found) {
            tu = mTu.elementAt(ind);
            if (copyGlobal) {
                tu.copyGlobal();
            }
        } else {
            tu = new TwitterUser(context, username, copyGlobal);
            mTu.add(tu);
        }
        return tu;
    }

    private static String fixUsername(String username) {
        if (username == null) {
            username = "";
        }
        username = username.trim();
        if (!isUsernameValid(username)) {
            username = "";
        }
        return username;
    }

    private TwitterUser(Context context, String username, boolean copyGlobal) {
        mContext = context;
        username = fixUsername(username);
        mPrefsFileName = SharedPreferencesUtil.prefsFileNameForUser(username);
        boolean isNewUser = !SharedPreferencesUtil.exists(mContext, mPrefsFileName);
        if (isNewUser) {
            // There was no data stored for this User
            // so let's start from temp file
            mPrefsFileName = SharedPreferencesUtil.prefsFileNameForUser("");
        }
        mSp = mContext.getSharedPreferences(mPrefsFileName, MODE_PRIVATE);
        setUsername(username, isNewUser);
        if (!isNewUser) {
            // Load stored data for the User
            mWasAuthenticated = mSp.getBoolean(PreferencesActivity.KEY_WAS_AUTHENTICATED, false);
            mCredentialsVerified = CredentialsVerified.load(mSp);
            mOAuth = mSp.getBoolean(PreferencesActivity.KEY_OAUTH, false);
            mPassword = mSp.getString(PreferencesActivity.KEY_TWITTER_PASSWORD, "");
        }
        if (copyGlobal) {
            copyGlobal();
        }
    }

    /**
     * @return the mUsername
     */
    public String getUsername() {
        return mUsername;
    }

    /**
     * set Username for the User who was first time authenticated
     * 
     * @param username - new Username to set.
     */
    private boolean setUsernameAuthenticated(String username) {
        username = fixUsername(username);
        String newPrefsFileName = SharedPreferencesUtil.prefsFileNameForUser(username);
        boolean ok = false;

        if (!mWasAuthenticated) {
            // Do we really need to change it?
            ok = (mPrefsFileName.compareTo(newPrefsFileName) == 0);
            if (!ok) {
                mConnection = null;
                mSp = null;
                ok = SharedPreferencesUtil.rename(mContext, mPrefsFileName, newPrefsFileName);

                if (ok) {
                    mPrefsFileName = newPrefsFileName;
                }
                mSp = mContext.getSharedPreferences(mPrefsFileName, MODE_PRIVATE);
                if (ok) {
                    // Now we know the name of this User!
                    setUsername(username, false);
                }
            }
            if (ok) {
                mWasAuthenticated = true;
                mSp.edit().putBoolean(PreferencesActivity.KEY_WAS_AUTHENTICATED, true).commit();
            }
        }
        return ok;
    }

    /**
     * @param username
     * @param isNewUser true is this object is reused for new user
     */
    private void setUsername(String username, boolean isNewUser) {
        mConnection = null;
        username = fixUsername(username);

        if (username.compareTo(mUsername) != 0) {
            if (isNewUser) {
                setCredentialsVerified(CredentialsVerified.NEVER);
                mSp.edit().putString(PreferencesActivity.KEY_TWITTER_USERNAME, mUsername).commit();
            }
            mConnection = null;
            mUsername = username;

            // Propagate the changes to the global properties
            PreferenceManager.getDefaultSharedPreferences(mContext).edit().putString(
                    PreferencesActivity.KEY_TWITTER_USERNAME, mUsername).commit();
        }
    }

    /**
     * Is this object - temporal (for user who was never authenticated)
     * 
     * @return
     */
    private boolean wasAuthenticated() {
        return mWasAuthenticated;
    }

    /**
     * Copy global (DefaultShared) preferences to this User's properties
     */
    private void copyGlobal() {
        // So the Connection object may reinitialize
        mConnection = null;
        SharedPreferences dsp = PreferenceManager.getDefaultSharedPreferences(mContext);
        boolean oauth = dsp.getBoolean(PreferencesActivity.KEY_OAUTH, false);
        if (mOAuth != oauth) {
            clearAuthInformation();
            setOAuth(oauth);
        }
        setPassword(dsp.getString(PreferencesActivity.KEY_TWITTER_PASSWORD, ""));
    }

    private static boolean isUsernameValid(String username) {
        boolean ok = false;
        if (username != null && (username.length() > 0)) {
            ok = Pattern.matches("[a-zA-Z_0-9\\.\\-\\(\\)]+", username);
            if (!ok && Log.isLoggable(AndTweetService.APPTAG, Log.INFO)) {
                Log.i(TAG, "The Username is not valid: \"" + username + "\"");
            }
        }
        return ok;
    }

    /**
     * Delete preferences file for this user
     * 
     * @param forNonAuthenticatedOnly
     * @return
     */
    public boolean deleteData() {
        boolean isDeleted = false;
        if (mPrefsFileName.length() > 0) {
            // Old preferences file may be deleted, if it exists...
            isDeleted = SharedPreferencesUtil.delete(mContext, mPrefsFileName);
        }
        return isDeleted;
    }

    /**
     * @param context
     * @return instance of Connection subtype for the User
     */
    public Connection getConnection() {
        if (mConnection == null) {
            mConnection = Connection.getConnection(mSp, mOAuth);
        }
        return mConnection;
    }

    /**
     * Clear Authentication information
     * 
     * @param context
     */
    public void clearAuthInformation() {
        setCredentialsVerified(CredentialsVerified.NEVER);
        setPassword("");
        this.getConnection().clearAuthInformation();
    }

    /**
     * @return the mOAuth
     */
    public boolean isOAuth() {
        return mOAuth;
    }

    /**
     * @param oAuth to set
     */
    private void setOAuth(boolean oauth) {
        if (mOAuth != oauth) {
            mConnection = null;
            mOAuth = oauth;
            mSp.edit().putBoolean(PreferencesActivity.KEY_OAUTH, oauth).commit();
        }
    }

    /**
     * @param oAuth to set
     */
    private void setPassword(String password) {
        if (password == null) {
            password = "";
        }
        if (password.compareTo(mPassword) != 0) {
            setCredentialsVerified(CredentialsVerified.NEVER);
            mConnection = null;
            mPassword = password;
            mSp.edit().putString(PreferencesActivity.KEY_TWITTER_PASSWORD, mPassword).commit();
            // Propagate the changes to the global properties
            PreferenceManager.getDefaultSharedPreferences(mContext).edit().putString(
                    PreferencesActivity.KEY_TWITTER_PASSWORD, mPassword).commit();
        }
    }

    /**
     * Verify the user's credentials. Returns true if authentication was
     * successful
     * 
     * @see CredentialsVerified
     * @param reVerify Verify even if it was verified already
     * @return boolean
     * @throws ConnectionException
     * @throws ConnectionUnavailableException
     * @throws ConnectionAuthenticationException
     * @throws SocketTimeoutException
     * @throws ConnectionCredentialsOfOtherUserException
     */
    public boolean verifyCredentials(boolean reVerify) throws ConnectionException,
            ConnectionUnavailableException, ConnectionAuthenticationException,
            SocketTimeoutException, ConnectionCredentialsOfOtherUserException {
        boolean ok = false;
        if (!reVerify) {
            if (getCredentialsVerified() == CredentialsVerified.SUCCEEDED) {
                ok = true;
            }
        }
        if (!ok) {
            JSONObject jso = null;
            try {
                jso = getConnection().verifyCredentials();
                ok = (jso != null);
            } finally {
                String newName = null;
                boolean credentialsOfOtherUser = false;
                boolean errorSettingUsername = false;
                if (ok) {
                    if (jso.optInt("id") < 1) {
                        ok = false;
                    }
                }
                if (ok) {
                    newName = Connection.getScreenName(jso);
                    ok = isUsernameValid(newName);
                }

                if (ok) {
                    if (getUsername().length() > 0 && getUsername().compareTo(newName) != 0) {
                        // Credentials belong to other User ??
                        ok = false;
                        credentialsOfOtherUser = true;
                    }
                }
                if (ok) {
                    setCredentialsVerified(CredentialsVerified.SUCCEEDED);
                }
                if (ok && !mWasAuthenticated) {
                    // Now we know the name of this User!
                    ok = setUsernameAuthenticated(newName);
                    if (!ok) {
                        errorSettingUsername = true;
                    }
                }
                if (!ok) {
                    clearAuthInformation();
                    setCredentialsVerified(CredentialsVerified.FAILED);
                }

                if (credentialsOfOtherUser) {
                    Log.e(TAG, mContext.getText(R.string.error_credentials_of_other_user) + ": " + newName);
                    throw (new ConnectionCredentialsOfOtherUserException());
                }
                if (errorSettingUsername) {
                    String msg = mContext.getText(R.string.error_set_username) + newName;
                    Log.e(TAG, msg);
                    throw (new ConnectionAuthenticationException(msg));
                }
            }
        }
        return ok;
    }

    /**
     * Update global (default) SharedPreferences
     */
    public void updateDefaultSharedPreferences() {
        // Update global SharedPreferences
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor ed = sp.edit();
        ed.putString(PreferencesActivity.KEY_TWITTER_USERNAME, getUsername());
        ed.putString(PreferencesActivity.KEY_TWITTER_PASSWORD, mPassword);
        ed.putBoolean(PreferencesActivity.KEY_OAUTH, isOAuth());
        getCredentialsVerified().put(ed);
        if (getCredentialsVerified() != CredentialsVerified.SUCCEEDED) {
            ed.putBoolean(PreferencesActivity.KEY_AUTOMATIC_UPDATES, false);
        }
        ed.commit();
    }
}