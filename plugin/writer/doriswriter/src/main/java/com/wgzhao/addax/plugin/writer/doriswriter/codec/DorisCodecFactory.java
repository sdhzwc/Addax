/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */


package com.wgzhao.addax.plugin.writer.doriswriter.codec;

import com.wgzhao.addax.plugin.writer.doriswriter.DorisKey;

public class DorisCodecFactory
{
    public DorisCodecFactory()
    {

    }

    public static DorisCodec createCodec(DorisKey writerOptions)
    {
        if (DorisKey.StreamLoadFormat.CSV.equals(writerOptions.getStreamLoadFormat())) {
            return new DorisCsvCodec(writerOptions.getColumnSeparator());
        }
        if (DorisKey.StreamLoadFormat.JSON.equals(writerOptions.getStreamLoadFormat())) {
            return new DorisJsonCodec(writerOptions.getColumns());
        }
        throw new RuntimeException("Failed to create row serializer, unsupported `format` from stream load properties.");
    }
}
