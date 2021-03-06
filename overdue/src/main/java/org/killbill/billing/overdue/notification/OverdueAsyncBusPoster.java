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

package org.killbill.billing.overdue.notification;

import java.util.Collection;

import org.joda.time.DateTime;
import org.skife.jdbi.v2.IDBI;

import org.killbill.clock.Clock;
import org.killbill.notificationq.api.NotificationEventWithMetadata;
import org.killbill.notificationq.api.NotificationQueue;
import org.killbill.notificationq.api.NotificationQueueService;
import org.killbill.billing.util.cache.CacheControllerDispatcher;
import org.killbill.billing.util.dao.NonEntityDao;
import org.killbill.billing.util.entity.dao.EntitySqlDao;
import org.killbill.billing.util.entity.dao.EntitySqlDaoWrapperFactory;

import com.google.inject.Inject;

public class OverdueAsyncBusPoster extends DefaultOverduePosterBase {

    @Inject
    public OverdueAsyncBusPoster(final NotificationQueueService notificationQueueService,
                                 final IDBI dbi, final Clock clock,
                                 final CacheControllerDispatcher cacheControllerDispatcher, final NonEntityDao nonEntityDao) {
        super(notificationQueueService, dbi, clock, cacheControllerDispatcher, nonEntityDao);
    }

    @Override
    protected <T extends OverdueCheckNotificationKey> boolean cleanupFutureNotificationsFormTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory,
                                                                                                        final Collection<NotificationEventWithMetadata<T>> futureNotifications,
                                                                                                        final DateTime futureNotificationTime,
                                                                                                        final NotificationQueue overdueQueue) {
        // If we already have notification for that account we don't insert the new one
        // Note that this is slightly incorrect because we could for instance already have a REFRESH and insert a CLEAR, but if that were the case,
        // if means overdue state would change very rapidly and the behavior would anyway be non deterministic
        //
        return futureNotifications.size() == 0;
    }

}
