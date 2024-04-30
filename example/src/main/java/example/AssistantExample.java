package example;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.ListSearchParameters;
import com.theokanning.openai.OpenAiResponse;
import com.theokanning.openai.assistants.StreamEvent;
import com.theokanning.openai.assistants.assistant.Assistant;
import com.theokanning.openai.assistants.assistant.AssistantRequest;
import com.theokanning.openai.assistants.assistant.FunctionTool;
import com.theokanning.openai.assistants.message.Message;
import com.theokanning.openai.assistants.message.MessageRequest;
import com.theokanning.openai.assistants.run.*;
import com.theokanning.openai.assistants.run_step.RunStep;
import com.theokanning.openai.assistants.thread.Thread;
import com.theokanning.openai.assistants.thread.ThreadRequest;
import com.theokanning.openai.service.FunctionExecutor;
import com.theokanning.openai.service.OpenAiService;
import com.theokanning.openai.service.assistant_stream.AssistantSSE;
import io.reactivex.Flowable;
import io.reactivex.subscribers.TestSubscriber;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * @author LiangTao
 * @date 2024年04月30 13:30
 **/
public class AssistantExample {
    public static void main(String[] args) {
        // assistantToolCall();
    }

    static void assistantToolCall() {
        OpenAiService service = new OpenAiService();
        FunctionExecutor executor = new FunctionExecutor(Collections.singletonList(ToolUtil.weatherFunction()));
        AssistantRequest assistantRequest = AssistantRequest.builder()
                .model("gpt-3.5-turbo").name("weather assistant")
                .instructions("You are a weather assistant responsible for calling the weather API to return weather information based on the location entered by the user")
                .tools(Collections.singletonList(new FunctionTool(ToolUtil.weatherFunction())))
                .temperature(0D)
                .build();
        Assistant assistant = service.createAssistant(assistantRequest);
        String assistantId = assistant.getId();
        ThreadRequest threadRequest = ThreadRequest.builder().build();
        Thread thread = service.createThread(threadRequest);
        String threadId = thread.getId();

        MessageRequest messageRequest = MessageRequest.builder()
                .content("What's the weather of Xiamen?")
                .build();
        //add message to thread
        service.createMessage(threadId, messageRequest);
        RunCreateRequest runCreateRequest = RunCreateRequest.builder().assistantId(assistantId).build();

        Run run = service.createRun(threadId, runCreateRequest);

        Run retrievedRun = service.retrieveRun(threadId, run.getId());
        while (!(retrievedRun.getStatus().equals("completed"))
                && !(retrievedRun.getStatus().equals("failed"))
                && !(retrievedRun.getStatus().equals("expired"))
                && !(retrievedRun.getStatus().equals("incomplete"))
                && !(retrievedRun.getStatus().equals("requires_action"))) {
            retrievedRun = service.retrieveRun(threadId, run.getId());
        }
        System.out.println(retrievedRun);

        RequiredAction requiredAction = retrievedRun.getRequiredAction();
        List<ToolCall> toolCalls = requiredAction.getSubmitToolOutputs().getToolCalls();
        ToolCall toolCall = toolCalls.get(0);
        ToolCallFunction function = toolCall.getFunction();
        String toolCallId = toolCall.getId();

        SubmitToolOutputsRequest submitToolOutputsRequest = SubmitToolOutputsRequest.ofSingletonToolOutput(toolCallId, executor.executeAndConvertToJson(function).toPrettyString());
        retrievedRun = service.submitToolOutputs(threadId, retrievedRun.getId(), submitToolOutputsRequest);

        while (!(retrievedRun.getStatus().equals("completed"))
                && !(retrievedRun.getStatus().equals("failed"))
                && !(retrievedRun.getStatus().equals("expired"))
                && !(retrievedRun.getStatus().equals("incomplete"))
                && !(retrievedRun.getStatus().equals("requires_action"))) {
            retrievedRun = service.retrieveRun(threadId, run.getId());
        }

        System.out.println(retrievedRun);

        OpenAiResponse<Message> response = service.listMessages(threadId, new ListSearchParameters());
        List<Message> messages = response.getData();
        messages.forEach(message -> {
            System.out.println(message.getContent());
        });

    }

    static void assistantStream() throws JsonProcessingException {
        OpenAiService service = new OpenAiService();
        String assistantId;
        String threadId;

        AssistantRequest assistantRequest = AssistantRequest.builder()
                .model("gpt-3.5-turbo").name("weather assistant")
                .instructions("You are a weather assistant responsible for calling the weather API to return weather information based on the location entered by the user")
                .tools(Collections.singletonList(new FunctionTool(ToolUtil.weatherFunction())))
                .temperature(0D)
                .build();
        Assistant assistant = service.createAssistant(assistantRequest);
        assistantId = assistant.getId();

        //一般响应
        Flowable<AssistantSSE> threadAndRunStream = service.createThreadAndRunStream(
                CreateThreadAndRunRequest.builder()
                        .assistantId(assistantId)
                        //这里不使用任何工具
                        .toolChoice(ToolChoice.NONE)
                        .thread(ThreadRequest.builder()
                                .messages(Collections.singletonList(
                                        MessageRequest.builder()
                                                .content("你好,你可以帮助我做什么?")
                                                .build()
                                ))
                                .build())
                        .build()
        );

        ObjectMapper objectMapper = new ObjectMapper();

        TestSubscriber<AssistantSSE> subscriber1 = new TestSubscriber<>();
        threadAndRunStream
                .doOnNext(System.out::println)
                .blockingSubscribe(subscriber1);

        Optional<AssistantSSE> runStepCompletion = subscriber1.values().stream().filter(item -> item.getEvent().equals(StreamEvent.THREAD_RUN_STEP_COMPLETED)).findFirst();
        RunStep runStep = objectMapper.readValue(runStepCompletion.get().getData(), RunStep.class);
        System.out.println(runStep.getStepDetails());


        // 函数调用 stream
        threadId = runStep.getThreadId();
        service.createMessage(threadId, MessageRequest.builder().content("请帮我查询北京天气").build());
        Flowable<AssistantSSE> getWeatherFlowable = service.createRunStream(threadId, RunCreateRequest.builder()
                //这里强制使用get_weather函数
                .assistantId(assistantId)
                .toolChoice(new ToolChoice(new Function("get_weather")))
                .build()
        );

        TestSubscriber<AssistantSSE> subscriber2 = new TestSubscriber<>();
        getWeatherFlowable
                .doOnNext(System.out::println)
                .blockingSubscribe(subscriber2);

        AssistantSSE requireActionSse = subscriber2.values().get(subscriber2.values().size() - 2);
        Run requireActionRun = objectMapper.readValue(requireActionSse.getData(), Run.class);
        RequiredAction requiredAction = requireActionRun.getRequiredAction();
        List<ToolCall> toolCalls = requiredAction.getSubmitToolOutputs().getToolCalls();
        ToolCall toolCall = toolCalls.get(0);
        String callId = toolCall.getId();

        System.out.println(toolCall.getFunction());


        // 提交函数调用结果
        Flowable<AssistantSSE> toolCallResponseFlowable = service.submitToolOutputsStream(threadId, requireActionRun.getId(), SubmitToolOutputsRequest.ofSingletonToolOutput(callId, "北京的天气是晴天"));
        TestSubscriber<AssistantSSE> subscriber3 = new TestSubscriber<>();
        toolCallResponseFlowable
                .doOnNext(System.out::println)
                .blockingSubscribe(subscriber3);

        Optional<AssistantSSE> msgSse = subscriber3.values().stream().filter(item -> StreamEvent.THREAD_MESSAGE_COMPLETED.equals(item.getEvent())).findFirst();
        Message message = objectMapper.readValue(msgSse.get().getData(), Message.class);
        String responseContent = message.getContent().get(0).getText().getValue();
        System.out.println(responseContent);
    }


}
