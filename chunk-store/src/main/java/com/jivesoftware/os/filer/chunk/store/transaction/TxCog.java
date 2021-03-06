/*
 * Copyright 2015 Jive Software.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jivesoftware.os.filer.chunk.store.transaction;

import com.jivesoftware.os.filer.io.CreateFiler;
import com.jivesoftware.os.filer.io.Filer;
import com.jivesoftware.os.filer.io.GrowFiler;
import com.jivesoftware.os.filer.io.OpenFiler;

/**
 *
 * @author jonathan.colt
 */
public class TxCog<H, M, F extends Filer> {
    public final CreateFiler<H, M, F>[] creators;
    public final OpenFiler<M, F> opener;
    public final GrowFiler<H, M, F> grower;

    public TxCog(CreateFiler<H, M, F>[] creator, OpenFiler<M, F> opener, GrowFiler<H, M, F> grower) {
        this.creators = creator;
        this.opener = opener;
        this.grower = grower;
    }

}
