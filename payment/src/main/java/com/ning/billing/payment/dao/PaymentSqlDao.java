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

package com.ning.billing.payment.dao;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.skife.jdbi.v2.sqlobject.Bind;
import org.skife.jdbi.v2.sqlobject.BindBean;
import org.skife.jdbi.v2.sqlobject.SqlQuery;
import org.skife.jdbi.v2.sqlobject.SqlUpdate;
import org.skife.jdbi.v2.sqlobject.customizers.FetchSize;

import com.ning.billing.callcontext.InternalCallContext;
import com.ning.billing.callcontext.InternalTenantContext;
import com.ning.billing.catalog.api.Currency;
import com.ning.billing.payment.api.Payment;
import com.ning.billing.util.audit.ChangeType;
import com.ning.billing.util.entity.dao.Audited;
import com.ning.billing.util.entity.dao.EntitySqlDao;
import com.ning.billing.util.entity.dao.EntitySqlDaoStringTemplate;

@EntitySqlDaoStringTemplate
public interface PaymentSqlDao extends EntitySqlDao<PaymentModelDao, Payment> {

    @SqlUpdate
    @Audited(ChangeType.UPDATE)
    void updatePaymentStatus(@Bind("id") final String paymentId,
                             @Bind("processedAmount") final BigDecimal processedAmount,
                             @Bind("processedCurrency") final Currency processedCurrency,
                             @Bind("paymentStatus") final String paymentStatus,
                             @BindBean final InternalCallContext context);

    @SqlUpdate
    @Audited(ChangeType.UPDATE)
    void updatePaymentForNewAttempt(@Bind("id") final String paymentId,
                                    @Bind("paymentMethodId") final String paymentMethodId,
                                    @Bind("amount") final BigDecimal amount,
                                    @Bind("effectiveDate") final Date effectiveDate,
                                    @BindBean final InternalCallContext context);

    @SqlQuery
    PaymentModelDao getLastPaymentForAccountAndPaymentMethod(@Bind("accountId") final String accountId,
                                                             @Bind("paymentMethodId") final String paymentMethodId,
                                                             @BindBean final InternalTenantContext context);

    @SqlQuery
    List<PaymentModelDao> getPaymentsForInvoice(@Bind("invoiceId") final String invoiceId,
                                                @BindBean final InternalTenantContext context);

    @SqlQuery
    List<PaymentModelDao> getPaymentsForAccount(@Bind("accountId") final String accountId,
                                                @BindBean final InternalTenantContext context);

    @SqlQuery
    // Magic value to force MySQL to stream from the database
    // See http://dev.mysql.com/doc/refman/5.0/en/connector-j-reference-implementation-notes.html (ResultSet)
    @FetchSize(Integer.MIN_VALUE)
    public Iterator<PaymentModelDao> getByPluginName(@Bind("pluginName") final String pluginName,
                                                     @Bind("offset") final Long offset,
                                                     @Bind("rowCount") final Long rowCount,
                                                     @BindBean final InternalTenantContext context);
}

