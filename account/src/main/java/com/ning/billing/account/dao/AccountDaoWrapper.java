/*
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.account.dao;

import java.util.List;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.IDBI;
import org.skife.jdbi.v2.TransactionCallback;
import org.skife.jdbi.v2.TransactionStatus;
import com.google.inject.Inject;
import com.ning.billing.account.api.Account;
import com.ning.billing.account.api.AccountChangeNotification;
import com.ning.billing.account.api.AccountCreationNotification;
import com.ning.billing.util.customfield.CustomField;
import com.ning.billing.account.api.DefaultAccount;
import com.ning.billing.util.customfield.FieldStore;
import com.ning.billing.util.customfield.dao.FieldStoreDao;
import com.ning.billing.util.tag.Tag;
import com.ning.billing.account.api.user.DefaultAccountChangeNotification;
import com.ning.billing.account.api.user.DefaultAccountCreationEvent;
import com.ning.billing.util.eventbus.EventBus;
import com.ning.billing.util.tag.dao.TagStoreDao;

public class AccountDaoWrapper implements AccountDao {
    private final AccountDao accountDao;
    private final IDBI dbi; // needed for transaction support
    private final EventBus eventBus;

    @Inject
    public AccountDaoWrapper(IDBI dbi, EventBus eventBus) {
        this.dbi = dbi;
        this.eventBus = eventBus;
        this.accountDao = dbi.onDemand(AccountDao.class);
    }

    @Override
    public Account getAccountByKey(final String key) {
        return dbi.inTransaction(new TransactionCallback<Account>() {
            @Override
            public Account inTransaction(Handle conn, TransactionStatus status) throws Exception {
                try {
                    conn.begin();
                    Account account = accountDao.getAccountByKey(key);

                    if (account != null) {
                        FieldStoreDao fieldStoreDao = conn.attach(FieldStoreDao.class);
                        List<CustomField> fields = fieldStoreDao.load(account.getId().toString(), account.getObjectName());

                        account.getFields().clear();
                        if (fields != null) {
                            for (CustomField field : fields) {
                                account.getFields().setValue(field.getName(), field.getValue());
                            }
                        }

                        TagStoreDao tagStoreDao = conn.attach(TagStoreDao.class);
                        List<Tag> tags = tagStoreDao.load(account.getId().toString(), account.getObjectName());
                        account.clearTags();

                        if (tags != null) {
                            account.addTags(tags);
                        }
                    }

                    return account;
                } catch (Throwable t) {
                    return null;
                }
            }
        });
    }

    @Override
    public Account getById(final String id) {
        return dbi.inTransaction(new TransactionCallback<Account>() {
            @Override
            public Account inTransaction(Handle conn, TransactionStatus status) throws Exception {
                Account account = accountDao.getById(id);

                if (account != null) {
                    FieldStoreDao fieldStoreDao = conn.attach(FieldStoreDao.class);
                    List<CustomField> fields = fieldStoreDao.load(account.getId().toString(), account.getObjectName());

                    account.getFields().clear();
                    if (fields != null) {
                        for (CustomField field : fields) {
                            account.getFields().setValue(field.getName(), field.getValue());
                        }
                    }

                    TagStoreDao tagStoreDao = conn.attach(TagStoreDao.class);
                    List<Tag> tags = tagStoreDao.load(account.getId().toString(), account.getObjectName());
                    account.clearTags();

                    if (tags != null) {
                        account.addTags(tags);
                    }
                }

                return account;
            }
        });
    }

    @Override
    public List<Account> get() {
        return accountDao.get();
    }

    @Override
    public void test() {
        accountDao.test();
    }

    @Override
    public void save(final Account account) {
        final String accountId = account.getId().toString();
        final String objectType = DefaultAccount.OBJECT_TYPE;

        dbi.inTransaction(new TransactionCallback<Void>() {
            @Override
            public Void inTransaction(Handle conn, TransactionStatus status) throws Exception {
                AccountDao accountDao = conn.attach(AccountDao.class);
                Account currentAccount = accountDao.getById(accountId);
                accountDao.save(account);

                FieldStore fieldStore = account.getFields();
                FieldStoreDao fieldStoreDao = conn.attach(FieldStoreDao.class);
                fieldStoreDao.save(accountId, objectType, fieldStore.getEntityList());

                TagStoreDao tagStoreDao = conn.attach(TagStoreDao.class);
                tagStoreDao.save(accountId, objectType, account.getTagList());

                if (currentAccount == null) {
                    AccountCreationNotification creationEvent = new DefaultAccountCreationEvent(account);
                    eventBus.post(creationEvent);
                } else {
                    AccountChangeNotification changeEvent = new DefaultAccountChangeNotification(account.getId(), currentAccount, account);
                    if (changeEvent.hasChanges()) {
                        eventBus.post(changeEvent);
                    }
                }

            return null;
            }
        });
    }
}
