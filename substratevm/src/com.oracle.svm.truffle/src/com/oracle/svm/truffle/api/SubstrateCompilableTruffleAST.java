/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.truffle.api;

import org.graalvm.compiler.truffle.common.TruffleCompilable;

import com.oracle.svm.core.deopt.SubstrateInstalledCode;
import com.oracle.svm.graal.meta.SubstrateCodeCacheProvider;

import jdk.vm.ci.code.InstalledCode;

public interface SubstrateCompilableTruffleAST extends TruffleCompilable, SubstrateInstalledCode.Factory {
    /**
     * Create a provisional {@link InstalledCode} object for code installation, as required by
     * infrastructure, but this object does not need to be the same {@link SubstrateInstalledCode}
     * object that is eventually installed in the code cache. Once the provisional object is passed
     * to {@link SubstrateCodeCacheProvider}, it creates the final {@link SubstrateInstalledCode}
     * object and invokes {@link SubstrateInstalledCode#setAddress} on it.
     *
     * @see SubstrateCodeCacheProvider
     */
    InstalledCode createPreliminaryInstalledCode();
}
