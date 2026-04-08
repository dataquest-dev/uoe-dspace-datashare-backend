/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare.event;



import org.apache.logging.log4j.Logger;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.event.Consumer;
import org.dspace.event.Event;

/**
 * This listens for events raised by DSpace for DataShare.
 */

public class DatashareConsumer implements Consumer {

    /**
     * log4j logger
     */
    private static Logger log = org.apache.logging.log4j.LogManager.getLogger(DatashareConsumer.class);

    private ItemService itemService =ContentServiceFactory.getInstance().getItemService();

    private DatashareEvent datashareEvent;

    @Override
    public void initialize() throws Exception {                                                       
    }

    @Override
    public void consume(Context ctx, Event event) throws Exception {
        if (this.datashareEvent == null && event.getSubjectType() == Constants.COLLECTION) {
			switch (event.getEventType()) {
			case Event.ADD: {
				DSpaceObject dso = event.getObject(ctx);
				if (dso instanceof Item) {
					Item item = (Item) dso;
					if (item.isArchived()) {
						// if a new item has been created and archived,
						// mark item for cleaning up
						this.datashareEvent = new DatashareEvent(item, event.getEventType());
					}
				}
				break;
			}
			case Event.REMOVE: {
				this.datashareEvent = new DatashareEvent(
						// event detail is the item handle
						event.getDetail(), event.getEventType());
				break;
			}
			default: {
				log.info("Unkown subject type: " + event.getSubjectType());
			}
			}
		}
    }

    @Override
    public void end(Context ctx) throws Exception {
        if (this.datashareEvent != null) {
			switch (this.datashareEvent.getType()) {
			case Event.ADD: {
				this.addItem(ctx, this.datashareEvent.getItem());
				break;
			}
			case Event.REMOVE: {
				// new ItemDataset(ctx, this.datashareEvent.getHandle()).delete();
				break;
			}
			default: {
				log.info("DatashareConsumer: Unknown event type: " + this.datashareEvent.getType());
			}
			}
			this.datashareEvent = null;
		}
    }

    @Override
    public void finish(Context ctx) throws Exception {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'finish'");
    }


   private void addItem(Context ctx, Item item) throws Exception {
		try {
			// // clear field used to store license type
			// DSpaceUtils.clearUserLicenseType(context, item);

			// // copy hijacked spatial country to dc.coverage.spatial
			// List<MetadataValue> vals = DSpaceUtils.getHijackedSpatial(item);
			// for (int i = 0; i < vals.size(); i++) {
			// 	MetaDataUtil.setSpatial(context, item, vals.get(i).getValue(), false);
			// }

			// // clear hijacked spatial field
			// DSpaceUtils.clearHijackedSpatial(context, item);

			log.info("DatashareConsumer: create dataset");

			// create zip file
			// new ItemDataset(item).createDataset();

			// ctx.turnOffAuthorisationSystem();

			// // commit changes
			// itemService.update(context, item);
		} catch (Exception ex) {
			throw new RuntimeException(ex);
        }
		// } catch (AuthorizeException ex) {
		// 	throw new RuntimeException(ex);
		// } finally {
		// 	context.restoreAuthSystemState();
		// }
	}
}
