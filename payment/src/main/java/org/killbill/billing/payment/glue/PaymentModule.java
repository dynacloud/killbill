/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2014 Groupon, Inc
 * Copyright 2014 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
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

package org.killbill.billing.payment.glue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.inject.Provider;

import org.killbill.automaton.DefaultStateMachineConfig;
import org.killbill.automaton.StateMachineConfig;
import org.killbill.billing.osgi.api.OSGIServiceRegistration;
import org.killbill.billing.payment.api.DefaultDirectPaymentApi;
import org.killbill.billing.payment.api.DefaultPaymentGatewayApi;
import org.killbill.billing.payment.api.DirectPaymentApi;
import org.killbill.billing.payment.api.PaymentGatewayApi;
import org.killbill.billing.payment.api.PaymentService;
import org.killbill.billing.payment.bus.InvoiceHandler;
import org.killbill.billing.payment.control.PaymentTagHandler;
import org.killbill.billing.payment.control.dao.InvoicePaymentControlDao;
import org.killbill.billing.payment.core.DirectPaymentProcessor;
import org.killbill.billing.payment.core.PaymentGatewayProcessor;
import org.killbill.billing.payment.core.PaymentMethodProcessor;
import org.killbill.billing.payment.core.PluginControlledPaymentProcessor;
import org.killbill.billing.payment.core.sm.PluginControlledDirectPaymentAutomatonRunner;
import org.killbill.billing.payment.dao.DefaultPaymentDao;
import org.killbill.billing.payment.dao.PaymentDao;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.payment.retry.BaseRetryService.RetryServiceScheduler;
import org.killbill.billing.payment.retry.DefaultRetryService;
import org.killbill.billing.payment.retry.DefaultRetryService.DefaultRetryServiceScheduler;
import org.killbill.billing.payment.retry.RetryService;
import org.killbill.billing.platform.api.KillbillConfigSource;
import org.killbill.billing.retry.plugin.api.PaymentControlPluginApi;
import org.killbill.billing.util.config.PaymentConfig;
import org.killbill.billing.util.glue.KillBillModule;
import org.killbill.xmlloader.XMLLoader;
import org.skife.config.ConfigurationObjectFactory;

import com.google.common.io.Resources;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;

public class PaymentModule extends KillBillModule {

    private static final String PLUGIN_THREAD_PREFIX = "Plugin-th-";

    public static final String PLUGIN_EXECUTOR_NAMED = "PluginExecutor";
    public static final String RETRYABLE_NAMED = "Retryable";

    public static final String STATE_MACHINE_RETRY = "RetryStateMachine";
    public static final String STATE_MACHINE_PAYMENT = "PaymentStateMachine";

    public PaymentModule(final KillbillConfigSource configSource) {
        super(configSource);
    }

    protected void installPaymentDao() {
        bind(PaymentDao.class).to(DefaultPaymentDao.class).asEagerSingleton();
        // Payment Control Plugin Dao
        bind(InvoicePaymentControlDao.class).asEagerSingleton();
    }

    protected void installPaymentProviderPlugins(final PaymentConfig config) {
    }

    protected void installRetryEngines() {
        bind(DefaultRetryService.class).asEagerSingleton();
        bind(RetryService.class).annotatedWith(Names.named(RETRYABLE_NAMED)).to(DefaultRetryService.class);

        bind(DefaultRetryServiceScheduler.class).asEagerSingleton();
        bind(RetryServiceScheduler.class).annotatedWith(Names.named(RETRYABLE_NAMED)).to(DefaultRetryServiceScheduler.class);
    }

    protected void installStateMachines() {

        bind(StateMachineProvider.class).annotatedWith(Names.named(STATE_MACHINE_RETRY)).toInstance(new StateMachineProvider("org/killbill/billing/payment/retry/RetryStates.xml"));
        bind(StateMachineConfig.class).annotatedWith(Names.named(STATE_MACHINE_RETRY)).toProvider(Key.get(StateMachineProvider.class, Names.named(STATE_MACHINE_RETRY)));

        bind(StateMachineProvider.class).annotatedWith(Names.named(STATE_MACHINE_PAYMENT)).toInstance(new StateMachineProvider("org/killbill/billing/payment/PaymentStates.xml"));
        bind(StateMachineConfig.class).annotatedWith(Names.named(STATE_MACHINE_PAYMENT)).toProvider(Key.get(StateMachineProvider.class, Names.named(STATE_MACHINE_PAYMENT)));
    }

    public static final class StateMachineProvider implements Provider<StateMachineConfig> {

        private final String stateMachineConfig;

        public StateMachineProvider(final String stateMachineConfig) {
            this.stateMachineConfig = stateMachineConfig;
        }

        @Override
        public StateMachineConfig get() {
            try {
                return XMLLoader.getObjectFromString(Resources.getResource(stateMachineConfig).toExternalForm(), DefaultStateMachineConfig.class);
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    protected void installAutomatonRunner() {
        bind(PluginControlledDirectPaymentAutomatonRunner.class).asEagerSingleton();
    }

    protected void installProcessors(final PaymentConfig paymentConfig) {
        final ExecutorService pluginExecutorService = Executors.newFixedThreadPool(paymentConfig.getPaymentPluginThreadNb(), new ThreadFactory() {

            @Override
            public Thread newThread(final Runnable r) {
                final Thread th = new Thread(r);
                th.setName(PLUGIN_THREAD_PREFIX + th.getId());
                return th;
            }
        });
        bind(ExecutorService.class).annotatedWith(Names.named(PLUGIN_EXECUTOR_NAMED)).toInstance(pluginExecutorService);
        bind(DirectPaymentProcessor.class).asEagerSingleton();
        bind(PluginControlledPaymentProcessor.class).asEagerSingleton();
        bind(PaymentGatewayProcessor.class).asEagerSingleton();
        bind(PaymentMethodProcessor.class).asEagerSingleton();
    }

    @Override
    protected void configure() {
        final ConfigurationObjectFactory factory = new ConfigurationObjectFactory(skifeConfigSource);
        final PaymentConfig paymentConfig = factory.build(PaymentConfig.class);

        bind(PaymentConfig.class).toInstance(paymentConfig);
        bind(new TypeLiteral<OSGIServiceRegistration<PaymentPluginApi>>() {}).toProvider(DefaultPaymentProviderPluginRegistryProvider.class).asEagerSingleton();
        bind(new TypeLiteral<OSGIServiceRegistration<PaymentControlPluginApi>>() {}).toProvider(DefaultPaymentControlProviderPluginRegistryProvider.class).asEagerSingleton();

        bind(DirectPaymentApi.class).to(DefaultDirectPaymentApi.class).asEagerSingleton();
        bind(PaymentGatewayApi.class).to(DefaultPaymentGatewayApi.class).asEagerSingleton();
        bind(InvoiceHandler.class).asEagerSingleton();
        bind(PaymentTagHandler.class).asEagerSingleton();
        bind(PaymentService.class).to(DefaultPaymentService.class).asEagerSingleton();
        installPaymentProviderPlugins(paymentConfig);
        installPaymentDao();
        installProcessors(paymentConfig);
        installStateMachines();
        installAutomatonRunner();
        installRetryEngines();
    }
}
