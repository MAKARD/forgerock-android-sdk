/*
 * Copyright (c) 2020 ForgeRock. All rights reserved.
 *
 * This software may be modified and distributed under the terms
 * of the MIT license. See the LICENSE file for details.
 */

package org.forgerock.android.auth;

import android.content.Context;

import org.forgerock.android.auth.exception.DuplicateMechanismException;
import org.forgerock.android.auth.exception.MechanismCreationException;
import org.forgerock.android.auth.exception.MechanismParsingException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Determines the type of mechanism which is being created, and routes the creation request to the
 * appropriate builder.
 */
abstract class MechanismFactory {

    private Context context;
    private StorageClient storageClient;

    private static final String TAG = MechanismFactory.class.getSimpleName();

    /**
     * Creates the MechanismFactory and loads the available mechanism information.
     */
    MechanismFactory(Context context, StorageClient storageClient) {
        this.context = context;
        this.storageClient = storageClient;
    }

    /**
     * Method used to create the Mechanism represented by a given URI.
     * @param version The version extracted from the URI.
     * @param mechanismUID A generated mechanismUID
     * @param map The map of values generated from the original URI.
     * @param listener Listener for receiving the mechanism registration result
     * @throws MechanismCreationException If anything goes wrong.
     */
    protected abstract void createFromUriParameters(int version, String mechanismUID, Map<String,
            String> map, FRAListener<Mechanism> listener)
            throws MechanismCreationException;

    /**
     * Return the MechanismParser subclass used by the factory for a particular Mechanism type.
     * @return The MechanismParser.
     */
    protected abstract MechanismParser getParser();

    /**
     * Convert a URL to the Mechanism it represents, including extracting the account.
     * Also adds it to the model.
     * @param uri The URI to process.
     * @param listener Listener for receiving the mechanism registration result
     */
    final void createFromUri(String uri, FRAListener<Mechanism> listener) {
        // Parse uri
        MechanismParser parser = getParser();
        Map<String, String> values = null;
        try {
            values = parser.map(uri);
        } catch (MechanismParsingException e) {
            listener.onException(e);
            return;
        }

        // Extract data and set default values accordingly
        String mechanismType = getFromMap(values, MechanismParser.SCHEME, "");
        String issuer = getFromMap(values, MechanismParser.ISSUER, "");
        String accountName = getFromMap(values, MechanismParser.ACCOUNT_NAME, "");
        String imageURL = getFromMap(values, MechanismParser.IMAGE, null);
        String bgColor = getFromMap(values, MechanismParser.BG_COLOR, null);

        // Check version
        int version = 0;
        try {
            version = Integer.parseInt(getFromMap(values, MechanismParser.VERSION, "1"));
        } catch (NumberFormatException e) {
            Logger.warn(TAG, e,"Expected valid integer, found: %s", values.get(MechanismParser.VERSION));
            listener.onException(new MechanismCreationException("Expected valid integer, found " +
                    values.get(MechanismParser.VERSION), e));
            return;
        }

        // Lookup the account and persist mechanism
        Logger.debug(TAG, "Lookup account for the new mechanism.");
        Account account = storageClient.getAccount(issuer + "-" + accountName);
        try {
            if (account == null) {
                Logger.debug(TAG, "Account not found. Creating new account for the mechanism.");
                account = Account.builder()
                        .setIssuer(issuer)
                        .setAccountName(accountName)
                        .setImageURL(imageURL)
                        .setBackgroundColor(bgColor)
                        .build();
                if(storageClient.setAccount(account)) {
                    Logger.debug(TAG, "New account stored successfully.");
                } else {
                    Logger.debug(TAG,"Failed to create an Account for the mechanism .");
                    listener.onException(new MechanismCreationException("Error while storing a new " +
                            "Account (" + account.getId() + ") for the new mechanism into the storage system."));
                    return;
                }
            } else {
                Logger.debug(TAG, "Account with id '%s' already exists.", account.getId());
                final List<Mechanism> mechanisms = storageClient.getMechanismsForAccount(account);
                for (Mechanism mechanism : mechanisms) {
                    if (mechanism.getType().equals(mechanismType)) {
                        listener.onException(new DuplicateMechanismException("Matching mechanism already exists", mechanism));
                        return;
                    }
                }
            }

            String mechanismUID = getNewMechanismUID();
            final Account finalAccount = account;
            createFromUriParameters(version, mechanismUID, values, new FRAListener<Mechanism>() {
                @Override
                public void onSuccess(Mechanism newMechanism) {
                    if(storageClient.setMechanism(newMechanism)) {
                        Logger.debug(TAG, "New mechanism with UID %s stored successfully.", mechanismUID);
                        listener.onSuccess(newMechanism);
                    } else {
                        Logger.debug(TAG,"Error storing the mechanism (%s) for the Account.", mechanismUID);
                        listener.onException(new MechanismCreationException("Error storing the mechanism for the Account."));
                    }
                }

                @Override
                public void onException(Exception e) {
                    listener.onException(e);
                    checkOrphanAccount(finalAccount);
                }
            });
        } catch (MechanismCreationException e) {
            checkOrphanAccount(account);
            listener.onException(e);
        }
    }

    /**
     * Get a context that this factory was created from.
     * @return The creating context.
     */
    Context getContext() {
        return context;
    }

    /**
     * Get a single value from the map.
     * @param map Map containing key pair values
     * @param name key to get stored value
     * @param defaultValue a value in case no value found for the key
     * @return The value for the name in the map or the default value
     */
    String getFromMap(Map<String, String> map, String name, String defaultValue) {
        String value = map.get(name);
        return value == null ? defaultValue : value;
    }

    /**
     * Generate a new, unique ID for a Mechanism.
     * @return The new mechanism UID.
     */
    private String getNewMechanismUID() {
        Logger.debug(TAG,"Creating new UID for the mechanism.");
        UUID uid = UUID.randomUUID();
        while (isExistingMechanismUID(uid.toString())) {
            uid = UUID.randomUUID();
        }
        return uid.toString();
    }

    /**
     * Check if unique ID for a Mechanism already exist
     * @return The new mechanism UID.
     */
    private boolean isExistingMechanismUID(String uid) {
        for (Mechanism mechanism : getAllMechanisms()) {
            if (mechanism.getMechanismUID().equals(uid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get all mechanisms stored in the model.
     * @return The complete list of mechanisms.
     */
    private List<Mechanism> getAllMechanisms() {
        List<Mechanism> result = new ArrayList<>();
        for (Account account : storageClient.getAllAccounts()) {
            result.addAll(storageClient.getMechanismsForAccount(account));
        }
        return result;
    }

    private void checkOrphanAccount(Account account) {
        if (storageClient.getMechanismsForAccount(account).isEmpty()) {
            Logger.debug(TAG,"Removing temporally created account.");
            storageClient.removeAccount(account);
        }
    }

}
