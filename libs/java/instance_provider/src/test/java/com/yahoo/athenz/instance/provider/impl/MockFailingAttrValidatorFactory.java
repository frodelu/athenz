/*
 * Copyright The Athenz Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.athenz.instance.provider.impl;

import com.yahoo.athenz.instance.provider.AttrValidator;
import com.yahoo.athenz.instance.provider.AttrValidatorFactory;
import com.yahoo.athenz.instance.provider.InstanceConfirmation;

import javax.net.ssl.SSLContext;

public class MockFailingAttrValidatorFactory implements AttrValidatorFactory {
    @Override
    public AttrValidator create(final SSLContext sslContext) {
        return instanceConfirmation -> false;
    }
}
