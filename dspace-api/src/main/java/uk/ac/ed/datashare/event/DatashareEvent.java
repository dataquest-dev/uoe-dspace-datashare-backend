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

