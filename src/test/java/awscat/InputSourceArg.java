package awscat;

import com.google.gson.JsonElement;

interface InputSourceArg {
    void setUp();

    void load(JsonElement jsonElement);

    String address();

    void tearDown();
}
