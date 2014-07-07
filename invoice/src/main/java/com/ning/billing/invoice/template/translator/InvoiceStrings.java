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

package com.ning.billing.invoice.template.translator;

public interface InvoiceStrings {

    String getInvoiceTitle();

    String getInvoiceDate();

    String getInvoiceNumber();

    String getAccountOwnerName();

    String getAccountOwnerEmail();

    String getAccountOwnerPhone();

    // company name and address
    String getCompanyName();

    String getCompanyAddress();

    String getCompanyCityProvincePostalCode();

    String getCompanyCountry();

    String getCompanyUrl();

    String getInvoiceItemBundleName();

    String getInvoiceItemDescription();

    String getInvoiceItemServicePeriod();

    String getInvoiceItemAmount();

    String getInvoiceItemAmountTax();
    
    String getInvoiceItemAmountExclTax();

    String getInvoiceAmount();

    String getInvoiceAmountTax();

    String getInvoiceAmountExclTax();

    String getInvoiceAmountPaid();

    String getInvoiceBalance();

    String getProcessedPaymentCurrency();

    String getProcessedPaymentRate();
}
