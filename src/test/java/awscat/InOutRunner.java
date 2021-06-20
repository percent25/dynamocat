package awscat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.google.gson.JsonElement;
import com.google.gson.JsonStreamParser;

public class InOutRunner {

  private final SourceArg source;
  private final TargetArg target;

  public InOutRunner(SourceArg source, TargetArg target) {
    this.source = source;
    this.target = target;
  }

  public void run(String json) {
    JsonElement jsonElement = jsonElement(json);
    source.setUp();
    try {
      target.setUp();
      try {
        JsonElement[] receivedJsonElement = new JsonElement[1];
        try {
          // load
          source.load(jsonElement);
          // invoke
          String sourceAddress = AwsBuilder.renderAddress(source.sourceArg());
          String targetAddress = AwsBuilder.renderAddress(target.targetArg());
          assertThatCode(() -> {
            Main.main(sourceAddress, targetAddress);
          }).doesNotThrowAnyException();
          // verify
          receivedJsonElement[0] = target.verify();
        } catch (Exception e) {
          e.printStackTrace();
        }
        assertThat(receivedJsonElement[0]).isEqualTo(jsonElement);
      } finally {
        target.tearDown();
      }
    } finally {
      source.tearDown();
    }
  }

  private JsonElement jsonElement(String json) {
    return new JsonStreamParser(json).next();
  }

}
