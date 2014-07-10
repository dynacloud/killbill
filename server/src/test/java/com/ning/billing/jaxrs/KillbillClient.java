/*
 * Copyright 2010-2013 Ning, Inc.
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

package com.ning.billing.jaxrs;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ning.billing.GuicyKillbillTestSuiteWithEmbeddedDB;
import com.ning.billing.catalog.api.BillingPeriod;
import com.ning.billing.catalog.api.PriceListSet;
import com.ning.billing.catalog.api.ProductCategory;
import com.ning.billing.client.KillBillClient;
import com.ning.billing.client.KillBillHttpClient;
import com.ning.billing.client.model.Account;
import com.ning.billing.client.model.PaymentMethod;
import com.ning.billing.client.model.PaymentMethodPluginDetail;
import com.ning.billing.client.model.PaymentMethodProperties;
import com.ning.billing.client.model.Subscription;

import static org.testng.Assert.assertNotNull;

public abstract class KillbillClient extends GuicyKillbillTestSuiteWithEmbeddedDB {

    protected static final String PLUGIN_NAME = "noop";

    protected static final String DEFAULT_CURRENCY = "USD";

    // Multi-Tenancy information, if enabled
    protected String DEFAULT_API_KEY = UUID.randomUUID().toString();
    protected String DEFAULT_API_SECRET = UUID.randomUUID().toString();

    // RBAC information, if enabled
    protected String USERNAME = "tester";
    protected String PASSWORD = "tester";

    // Context information to be passed around
    protected static final String createdBy = "Toto";
    protected static final String reason = "i am god";
    protected static final String comment = "no comment";

    protected KillBillClient killBillClient;
    protected KillBillHttpClient killBillHttpClient;

    protected List<PaymentMethodProperties> getPaymentMethodCCProperties() {
        final List<PaymentMethodProperties> properties = new ArrayList<PaymentMethodProperties>();
        properties.add(new PaymentMethodProperties("type", "CreditCard", false));
        properties.add(new PaymentMethodProperties("cardType", "Visa", false));
        properties.add(new PaymentMethodProperties("cardHolderName", "Mr Sniff", false));
        properties.add(new PaymentMethodProperties("expirationDate", "2015-08", false));
        properties.add(new PaymentMethodProperties("maskNumber", "3451", false));
        properties.add(new PaymentMethodProperties("address1", "23, rue des cerisiers", false));
        properties.add(new PaymentMethodProperties("address2", "", false));
        properties.add(new PaymentMethodProperties("city", "Toulouse", false));
        properties.add(new PaymentMethodProperties("country", "France", false));
        properties.add(new PaymentMethodProperties("postalCode", "31320", false));
        properties.add(new PaymentMethodProperties("state", "Midi-Pyrenees", false));
        return properties;
    }

    protected List<PaymentMethodProperties> getPaymentMethodPaypalProperties() {
        final List<PaymentMethodProperties> properties = new ArrayList<PaymentMethodProperties>();
        properties.add(new PaymentMethodProperties("type", "CreditCard", false));
        properties.add(new PaymentMethodProperties("email", "zouzou@laposte.fr", false));
        properties.add(new PaymentMethodProperties("baid", "23-8787d-R", false));
        return properties;
    }

    protected Account createAccountWithDefaultPaymentMethod() throws Exception {
        final Account input = createAccount();

        final PaymentMethodPluginDetail info = new PaymentMethodPluginDetail();
        final PaymentMethod paymentMethodJson = new PaymentMethod(null, input.getAccountId(), true, PLUGIN_NAME, info);
        killBillClient.createPaymentMethod(paymentMethodJson, createdBy, reason, comment);

        return killBillClient.getAccount(input.getExternalKey());
    }

    protected Account createAccount() throws Exception {
        final Account input = getAccount();
        return killBillClient.createAccount(input, createdBy, reason, comment);
    }

    protected Subscription createEntitlement(final UUID accountId, final String bundleExternalKey, final String productName,
                                             final ProductCategory productCategory, final BillingPeriod billingPeriod, final boolean waitCompletion) throws Exception {
        final Subscription input = new Subscription();
        input.setAccountId(accountId);
        input.setExternalKey(bundleExternalKey);
        input.setProductName(productName);
        input.setProductCategory(productCategory);
        input.setBillingPeriod(billingPeriod);
        input.setPriceList(PriceListSet.DEFAULT_PRICELIST_NAME);

        return killBillClient.createSubscription(input, waitCompletion ? 5 : -1, createdBy, reason, comment);
    }

    protected Account createAccountWithPMBundleAndSubscriptionAndWaitForFirstInvoice() throws Exception {
        final Account accountJson = createAccountWithDefaultPaymentMethod();
        assertNotNull(accountJson);

        // Add a bundle, subscription and move the clock to get the first invoice
        final Subscription subscriptionJson = createEntitlement(accountJson.getAccountId(), UUID.randomUUID().toString(), "Shotgun",
                                                                ProductCategory.BASE, BillingPeriod.MONTHLY, true);
        assertNotNull(subscriptionJson);
        clock.addDays(32);
        crappyWaitForLackOfProperSynchonization();

        return accountJson;
    }

    protected Account createAccountNoPMBundleAndSubscriptionAndWaitForFirstInvoice() throws Exception {
        // Create an account with no payment method
        final Account accountJson = createAccount();
        assertNotNull(accountJson);

        // Add a bundle, subscription and move the clock to get the first invoice
        final Subscription subscriptionJson = createEntitlement(accountJson.getAccountId(), UUID.randomUUID().toString(), "Shotgun",
                                                                ProductCategory.BASE, BillingPeriod.MONTHLY, true);
        assertNotNull(subscriptionJson);
        clock.addMonths(1);
        crappyWaitForLackOfProperSynchonization();

        // No payment will be triggered as the account doesn't have a payment method

        return accountJson;
    }

    protected Account getAccount() {
        return getAccount(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString().substring(0, 5) + '@' + UUID.randomUUID().toString().substring(0, 5));
    }

    public Account getAccount(final String name, final String externalKey, final String email) {
        final UUID accountId = UUID.randomUUID();
        final int length = 4;
        final String currency = DEFAULT_CURRENCY;
        final String timeZone = "UTC";
        final String address1 = "12 rue des ecoles";
        final String address2 = "Poitier";
        final String postalCode = "44 567";
        final String company = "Renault";
        final String city = "Quelque part";
        final String state = "Poitou";
        final String country = "France";
        final String locale = "fr";
        final String phone = "81 53 26 56";

        // Note: the accountId payload is ignored on account creation
        return new Account(accountId, name, length, externalKey, email, null, currency, null, timeZone,
                           address1, address2, postalCode, company, city, state, country, locale, phone, false, false, null, null);
    }

    /**
     * We could implement a ClockResource in jaxrs with the ability to sync on user token
     * but until we have a strong need for it, this is in the TODO list...
     */
    protected void crappyWaitForLackOfProperSynchonization() throws Exception {
        Thread.sleep(5000);
    }
}
