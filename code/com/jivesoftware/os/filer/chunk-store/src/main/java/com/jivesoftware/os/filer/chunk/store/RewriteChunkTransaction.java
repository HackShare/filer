/*
 * Copyright 2014 Jive Software.
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
package com.jivesoftware.os.filer.chunk.store;

import com.jivesoftware.os.filer.io.chunk.ChunkFiler;
import java.io.IOException;

/**
 *
 * @author jonathan.colt
 */
public interface RewriteChunkTransaction<M, R> {

    R commit(M currentMonkey, ChunkFiler currentFiler, M newMonkey, ChunkFiler newFiler, Object currentLock, Object newLock) throws IOException;
}
