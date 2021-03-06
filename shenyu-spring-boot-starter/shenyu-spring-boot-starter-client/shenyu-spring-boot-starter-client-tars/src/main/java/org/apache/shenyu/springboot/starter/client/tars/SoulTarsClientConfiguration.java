/*
 *   Licensed to the Apache Software Foundation (ASF) under one or more
 *   contributor license agreements.  See the NOTICE file distributed with
 *   this work for additional information regarding copyright ownership.
 *   The ASF licenses this file to You under the Apache License, Version 2.0
 *   (the "License"); you may not use this file except in compliance with
 *   the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */

package org.apache.shenyu.springboot.starter.client.tars;

import org.apache.shenyu.client.tars.TarsServiceBeanPostProcessor;
import org.apache.shenyu.register.client.api.SoulClientRegisterRepository;
import org.apache.shenyu.register.common.config.SoulRegisterCenterConfig;
import org.apache.shenyu.springboot.starter.client.common.config.SoulClientCommonBeanConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tars type client bean postprocessor.
 *
 * @author tydhot
 */
@Configuration
@ImportAutoConfiguration(SoulClientCommonBeanConfiguration.class)
public class SoulTarsClientConfiguration {
    /**
     * Tars service bean post processor sofa service bean post processor.
     *
     * @param tarsConfig the tars config
     * @param soulClientRegisterRepository the soulClientRegisterRepository
     * @return the tars service bean post processor
     */
    @Bean
    public TarsServiceBeanPostProcessor tarsServiceBeanPostProcessor(final SoulRegisterCenterConfig tarsConfig, final SoulClientRegisterRepository soulClientRegisterRepository) {
        return new TarsServiceBeanPostProcessor(tarsConfig, soulClientRegisterRepository);
    }
    
}
