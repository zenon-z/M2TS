package de.tum.tssimulator.cpr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Property;

class PropertyLinkTest {

    @Test
    void rejectsSameModelPair() {
        Model model = new ModelFactory(1).createModels(List.of(2)).get(0);
        Property source = model.properties().get(0);
        Property target = model.properties().get(1);

        assertThrows(IllegalArgumentException.class, () -> new PropertyLink(source, target));
    }

    @Test
    void acceptsCrossModelPair() {
        List<Model> models = new ModelFactory(1).createModels(List.of(1, 1));
        Property source = models.get(0).properties().get(0);
        Property target = models.get(1).properties().get(0);

        PropertyLink link = assertDoesNotThrow(() -> new PropertyLink(source, target));
        assertEquals(source, link.source());
        assertEquals(target, link.target());
    }
}
