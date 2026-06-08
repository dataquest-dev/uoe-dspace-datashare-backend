/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare.event;

import java.sql.SQLException;
import java.util.Collections;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.event.Event;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link DatashareTemporalCoverageConsumer}.
 */
public class DatashareTemporalCoverageConsumerTest {

    private DatashareTemporalCoverageConsumer newConsumer(ItemService itemService) throws Exception {
        DatashareTemporalCoverageConsumer consumer = new DatashareTemporalCoverageConsumer();
        // inject the mock directly (avoid initialize(), which needs the running kernel)
        FieldUtils.writeField(consumer, "itemService", itemService, true);
        return consumer;
    }

    private Event installEvent(Context ctx, Item item) throws SQLException {
        Event event = Mockito.mock(Event.class);
        Mockito.when(event.getSubjectType()).thenReturn(Constants.ITEM);
        Mockito.when(event.getEventType()).thenReturn(Event.INSTALL);
        Mockito.when(event.getSubject(ctx)).thenReturn(item);
        return event;
    }

    /**
     * On archive, when the canonical dc.coverage.temporal value exists, the individual
     * dc.coverage.startDate / dc.coverage.endDate fields are removed (and the item is updated).
     */
    @Test
    public void removesIndividualDateFieldsOnArchiveWhenTemporalPresent() throws Exception {
        ItemService itemService = Mockito.mock(ItemService.class);
        Context ctx = Mockito.mock(Context.class);
        Item item = Mockito.mock(Item.class);
        Mockito.when(item.isArchived()).thenReturn(true);
        Mockito.when(itemService.getMetadataByMetadataString(item, "dc.coverage.temporal"))
            .thenReturn(Collections.singletonList(Mockito.mock(MetadataValue.class)));
        Mockito.when(itemService.getMetadataByMetadataString(item, "dc.coverage.startDate"))
            .thenReturn(Collections.singletonList(Mockito.mock(MetadataValue.class)));
        Mockito.when(itemService.getMetadataByMetadataString(item, "dc.coverage.endDate"))
            .thenReturn(Collections.singletonList(Mockito.mock(MetadataValue.class)));

        DatashareTemporalCoverageConsumer consumer = newConsumer(itemService);
        consumer.consume(ctx, installEvent(ctx, item));
        consumer.end(ctx);

        Mockito.verify(itemService).clearMetadata(ctx, item, "dc", "coverage", "startDate", Item.ANY);
        Mockito.verify(itemService).clearMetadata(ctx, item, "dc", "coverage", "endDate", Item.ANY);
        // temporal is the canonical value and must NOT be cleared here
        Mockito.verify(itemService, Mockito.never()).clearMetadata(ctx, item, "dc", "coverage", "temporal", Item.ANY);
        Mockito.verify(itemService).update(ctx, item);
    }

    /**
     * If there is no canonical dc.coverage.temporal value (e.g. an incomplete date pair), the
     * individual date fields are left untouched to avoid losing temporal information.
     */
    @Test
    public void keepsIndividualDateFieldsWhenTemporalAbsent() throws Exception {
        ItemService itemService = Mockito.mock(ItemService.class);
        Context ctx = Mockito.mock(Context.class);
        Item item = Mockito.mock(Item.class);
        Mockito.when(item.isArchived()).thenReturn(true);
        Mockito.when(itemService.getMetadataByMetadataString(item, "dc.coverage.temporal"))
            .thenReturn(Collections.emptyList());

        DatashareTemporalCoverageConsumer consumer = newConsumer(itemService);
        consumer.consume(ctx, installEvent(ctx, item));
        consumer.end(ctx);

        Mockito.verify(itemService, Mockito.never())
            .clearMetadata(ctx, item, "dc", "coverage", "startDate", Item.ANY);
        Mockito.verify(itemService, Mockito.never())
            .clearMetadata(ctx, item, "dc", "coverage", "endDate", Item.ANY);
        Mockito.verify(itemService, Mockito.never()).update(ctx, item);
    }

    /**
     * Non-INSTALL events (e.g. modifications during submission/workflow) must not strip the fields,
     * so the dates remain editable while the item is still in progress.
     */
    @Test
    public void ignoresNonInstallEvents() throws Exception {
        ItemService itemService = Mockito.mock(ItemService.class);
        Context ctx = Mockito.mock(Context.class);
        Event modify = Mockito.mock(Event.class);
        Mockito.when(modify.getSubjectType()).thenReturn(Constants.ITEM);
        Mockito.when(modify.getEventType()).thenReturn(Event.MODIFY_METADATA);

        DatashareTemporalCoverageConsumer consumer = newConsumer(itemService);
        consumer.consume(ctx, modify);
        consumer.end(ctx);

        Mockito.verifyNoInteractions(itemService);
    }
}
