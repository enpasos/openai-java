package example;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.theokanning.openai.completion.chat.*;
import com.theokanning.openai.service.FunctionExecutor;
import com.theokanning.openai.service.OpenAiService;
import io.reactivex.Flowable;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class DBExample {

    public static void main(String... args) {
        String token = System.getenv("OPENAI_TOKEN");
        OpenAiService service = new OpenAiService(token);

        String postPrompt = ""; // Don’t give information not mentioned in the provided context.";

        FunctionExecutor functionExecutor = new FunctionExecutor(Collections.singletonList(ChatFunction.builder()
                .name("get_train")
                .description("Get the next train going from start_station to target_station")
                .executor(GetTrainRequest.class, w -> {
                    System.out.println("Zug von eva="+w.startStation +" nach eva="+w.targetStation +" gesucht.");

                  return new GetTrainResponse(
                           "Hauptbahnhof Frankfurt am Main",
                          "Hauptbahnhof München",
                          "ICE 1234");
                })
                .build()));

        List<ChatMessage> messages = new ArrayList<>();
     //   ChatMessage systemMessage = new ChatMessage(ChatMessageRole.SYSTEM.value(), "Du bist der experimentelle ChatAssistent der Deutschen Bahn.  Du sprichst nur über Bahn und Sachverhalte, die damit in direktem Zusammenhang stehen. Wenn Deine Information nicht aus dem FunctionCall kommt, verweist Du eher auf die Internetseiten der Deutschen Bahn oder den DB Navigator, als Dir etwas auszudenken. Wenn Du den Namen für einen Bahnhof aus dem FunctionCall bekommst, verwendest Du diesen in Deiner Antwort. ");
        ChatMessage systemMessage = new ChatMessage(ChatMessageRole.SYSTEM.value(),
                "Du bist der experimentelle ChatAssistent der Deutschen Bahn. Your role is helpful assistant. " +
                        "Whatever the user asks, you only give information concerning trains. Never about something else. " +
                        "Wenn Du den Namen für einen Bahnhof aus dem FunctionCall bekommst, verwendest Du diesen in Deiner Antwort. ");
        messages.add(systemMessage);

        System.out.print("First Query: ");
        Scanner scanner = new Scanner(System.in);
        ChatMessage firstMsg = new ChatMessage(ChatMessageRole.USER.value(), scanner.nextLine() + postPrompt);
        messages.add(firstMsg);

        while (true) {
            ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest
                    .builder()
                    .model("gpt-3.5-turbo-0613")
                    .messages(messages)
                    .functions(functionExecutor.getFunctions())
                    .functionCall(ChatCompletionRequest.ChatCompletionRequestFunctionCall.of("auto"))
                    .n(1)
                    .maxTokens(256)
                    .logitBias(new HashMap<>())
                    .build();
            Flowable<ChatCompletionChunk> flowable = service.streamChatCompletion(chatCompletionRequest);

            AtomicBoolean isFirst = new AtomicBoolean(true);
            ChatMessage chatMessage = service.mapStreamToAccumulator(flowable)
                    .doOnNext(accumulator -> {
                        if (accumulator.isFunctionCall()) {
                            if (isFirst.getAndSet(false)) {
                                System.out.println("Executing function " + accumulator.getAccumulatedChatFunctionCall().getName() + "...");
                            }
                        } else {
                            if (isFirst.getAndSet(false)) {
                                System.out.print("Response: ");
                            }
                            if (accumulator.getMessageChunk().getContent() != null) {
                                System.out.print(accumulator.getMessageChunk().getContent());
                            }
                        }
                    })
                    .doOnComplete(System.out::println)
                    .lastElement()
                    .blockingGet()
                    .getAccumulatedMessage();
            messages.add(chatMessage); // don't forget to update the conversation with the latest response

            if (chatMessage.getFunctionCall() != null) {
                System.out.println("Trying to execute " + chatMessage.getFunctionCall().getName() + "...");
                ChatMessage functionResponse = functionExecutor.executeAndConvertToMessageHandlingExceptions(chatMessage.getFunctionCall());
                System.out.println("Executed " + chatMessage.getFunctionCall().getName() + ".");
                messages.add(functionResponse);
                continue;
            }

            System.out.print("Next Query: ");
            String nextLine = scanner.nextLine();
            if (nextLine.equalsIgnoreCase("exit")) {
                System.exit(0);
            }
            messages.add(new ChatMessage(ChatMessageRole.USER.value(), nextLine + postPrompt));
        }
    }


    public static class GetTrainRequest {
        @JsonPropertyDescription("EVA-Nummer des Startbahnhofs")
        public int startStation;

        @JsonPropertyDescription("EVA-Nummer des Zielbahnhofs")
        public int targetStation;
    }




    public static class GetTrainResponse {

        @JsonPropertyDescription("Name des Startbahnhofs")
        public String nameOfStartStation;
        @JsonPropertyDescription("Name des Zielbahnhofs")
        public String nameOfTargetStation;

        public String zugNummer;

        public GetTrainResponse(String nameOfStartStation, String nameOfTargetStation, String zugNummer) {
            this.nameOfStartStation = nameOfStartStation;
            this.nameOfTargetStation = nameOfTargetStation;
            this.zugNummer = zugNummer;
        }
    }

}
