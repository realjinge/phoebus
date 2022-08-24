/*******************************************************************************
 * Copyright (c) 2017-2022 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.phoebus.pv;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.Collection;
import java.util.prefs.Preferences;

import org.junit.Test;
import org.phoebus.pv.ca.JCA_Preferences;

/** @author Kay Kasemir */
@SuppressWarnings("nls")
public class PVPoolTest
{
    @Test
    public void listPrefixes()
    {
        final Collection<String> prefs = PVPool.getSupportedPrefixes();
        System.out.println("Prefixes: " + prefs);
        assertThat(prefs, hasItem("ca"));
        assertThat(prefs, hasItem("sim"));
    }

    @Test
    public void analyzePVs()
    {
        String[] type_name = PVPool.analyzeName("pva://ramp");
        assertThat(type_name[0], equalTo("pva"));
        assertThat(type_name[1], equalTo("ramp"));

        type_name = PVPool.analyzeName("ramp");
        assertThat(type_name[0], equalTo(PVPool.default_type));
        assertThat(type_name[1], equalTo("ramp"));
    }

    @Test
    public void dumpPreferences() throws Exception
    {
        JCA_Preferences.getInstance();
        final Preferences prefs = Preferences.userNodeForPackage(PV.class);
        prefs.exportSubtree(System.out);
    }
}
