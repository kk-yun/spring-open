package net.onrc.onos.core.topology.web.serializers;

import java.io.IOException;

import net.onrc.onos.core.topology.Port;
import net.onrc.onos.core.topology.Switch;

import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.map.SerializerProvider;
import org.codehaus.jackson.map.ser.std.SerializerBase;

public class SwitchSerializer extends SerializerBase<Switch> {

    public SwitchSerializer() {
        super(Switch.class);
    }

    @Override
    public void serialize(Switch sw, JsonGenerator jsonGenerator,
                          SerializerProvider serializerProvider) throws IOException {
        //
        // TODO: For now, the JSON format of the serialized output should
        // be same as the JSON format of the corresponding class SwitchEvent.
        // In the future, we will use a single serializer.
        //

        jsonGenerator.writeStartObject();
        jsonGenerator.writeStringField("dpid", sw.getDpid().toString());
        jsonGenerator.writeStringField("state", "ACTIVE");
        jsonGenerator.writeArrayFieldStart("ports");
        for (Port port : sw.getPorts()) {
            jsonGenerator.writeObject(port);
        }
        jsonGenerator.writeEndArray();
        jsonGenerator.writeEndObject();
    }
}
