/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare.event;

import org.dspace.content.Item;


public class DatashareEvent {
    private Item item;
    private String handle;
    private int type;

    public DatashareEvent(Item item, int type) {
        this.item = item;
        this.type = type;
    }

    public DatashareEvent(String handle, int type) {
        this.handle = handle;
        this.type = type;
    }

    public Item getItem() {
        return item;
    }

    public String getHandle() {
        return handle;
    }

    public int getType() {
        return type;
    }
}

