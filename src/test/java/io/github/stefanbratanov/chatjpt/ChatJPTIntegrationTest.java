package io.github.stefanbratanov.chatjpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.stefanbratanov.chatjpt.ChatMessage.UserMessage.UserMessageWithContentParts.ContentPart.TextContentPart;
import io.github.stefanbratanov.chatjpt.FineTuningClient.PaginatedFineTuningEvents;
import io.github.stefanbratanov.chatjpt.FineTuningClient.PaginatedFineTuningJobs;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ChatJPTIntegrationTest extends ChatJPTIntegrationTestBase {

  @Test
  public void testUnauthorizedRequest() {
    ChatJPT unauthorizedChatJPT = ChatJPT.newBuilder("foobar").build();

    OpenAIException exception =
        assertThrows(OpenAIException.class, () -> unauthorizedChatJPT.modelsClient().listModels());

    assertThat(exception.statusCode()).isEqualTo(401);
    assertThat(exception.errorMessage()).startsWith("Incorrect API key provided: foobar");
  }

  @Test
  public void testChatClient() {
    ChatClient chatClient = chatJPT.chatClient();

    ChatRequest request =
        ChatRequest.newBuilder()
            .message(ChatMessage.userMessage("Who won the world series in 2020?"))
            .build();

    ChatResponse response = chatClient.sendRequest(request);

    assertThat(response.choices())
        .hasSize(1)
        .first()
        .satisfies(choice -> assertThat(choice.message().content()).isNotNull());

    // test streaming
    ChatRequest streamRequest =
        ChatRequest.newBuilder()
            // test sending content part
            .message(ChatMessage.userMessage(new TextContentPart("Say this is a test")))
            .stream(true)
            .build();

    String joinedResponse =
        chatClient
            .sendStreamRequest(streamRequest)
            .map(ChatChunkResponse::choices)
            .map(
                choices -> {
                  assertThat(choices).hasSize(1);
                  return choices.get(0).delta().content();
                })
            .filter(Objects::nonNull)
            .collect(Collectors.joining());

    assertThat(joinedResponse).isEqualTo("This is a test.");
  }

  @Test
  public void testModelsClient() {
    ModelsClient modelsClient = chatJPT.modelsClient();

    List<Model> models = modelsClient.listModels();

    assertThat(models).isNotEmpty();

    Model model = modelsClient.retrieveModel("gpt-3.5-turbo-instruct");

    assertThat(model).isNotNull();
  }

  @Test
  public void testAudioClient(@TempDir Path tempDir) {
    AudioClient audioClient = chatJPT.audioClient();

    SpeechRequest speechRequest =
        SpeechRequest.newBuilder()
            .model("tts-1")
            .input("The quick brown fox jumped over the lazy dog.")
            .voice("alloy")
            .build();

    Path speech = tempDir.resolve("speech.mp3");

    // test async
    assertThat(audioClient.createSpeechAsync(speechRequest, speech))
        .succeedsWithin(Duration.ofMinutes(1));

    assertThat(speech).exists().isNotEmptyFile();

    TranscriptionRequest transcriptionRequest =
        TranscriptionRequest.newBuilder().file(speech).model("whisper-1").build();

    String transcript = audioClient.createTranscript(transcriptionRequest);

    assertThat(transcript).isEqualToIgnoringCase("The quick brown fox jumped over the lazy dog.");

    Path greeting = getTestResource("/italian-greeting.mp3");

    TranslationRequest translationRequest =
        TranslationRequest.newBuilder().file(greeting).model("whisper-1").build();

    String translation = audioClient.createTranslation(translationRequest);

    assertThat(translation).isEqualTo("My name is Diego. What's your name?");
  }

  @Test
  @Disabled("Image models are costly")
  public void testImagesClient() {
    ImagesClient imagesClient = chatJPT.imagesClient();

    CreateImageRequest createImageRequest =
        CreateImageRequest.newBuilder()
            .prompt("Create a duck dressed up as superman")
            .responseFormat("b64_json")
            .build();

    Images createdImage = imagesClient.createImage(createImageRequest);

    assertThat(createdImage.data())
        .hasSize(1)
        .allSatisfy(image -> assertThat(image.b64Json()).isNotEmpty());

    Path duck = getTestResource("/duck.png");

    EditImageRequest editImageRequest =
        EditImageRequest.newBuilder().image(duck).prompt("Make the duck swim in water").build();

    Images editedImage = imagesClient.editImage(editImageRequest);

    assertThat(editedImage.data())
        .hasSize(1)
        .allSatisfy(image -> assertThat(image.url()).isNotNull());

    Path duckSuperman = getTestResource("/duck-superman.png");

    CreateImageVariationRequest createImageVariationRequest =
        CreateImageVariationRequest.newBuilder().image(duckSuperman).n(2).build();

    // test async
    CompletableFuture<Images> imageVariationsFuture =
        imagesClient.createImageVariationAsync(createImageVariationRequest);

    assertThat(imageVariationsFuture)
        .succeedsWithin(Duration.ofMinutes(1))
        .satisfies(
            imageVariations -> {
              assertThat(imageVariations.data())
                  .hasSize(2)
                  .allSatisfy(image -> assertThat(image.url()).isNotNull());
            });
  }

  @Test
  public void testModerationsClient() {
    ModerationsClient moderationsClient = chatJPT.moderationsClient();

    ModerationRequest request =
        ModerationRequest.newBuilder().input("I want to kill them.").build();

    Moderation moderation = moderationsClient.createModeration(request);

    assertThat(moderation.results())
        .hasSize(1)
        .allSatisfy(result -> assertThat(result.flagged()).isTrue());
  }

  @Test
  public void testEmbeddingsClient() {
    EmbeddingsClient embeddingsClient = chatJPT.embeddingsClient();

    EmbeddingsRequest request =
        EmbeddingsRequest.newBuilder()
            .input("The food was delicious and the waiter...")
            .model("text-embedding-ada-002")
            .build();

    Embeddings embeddings = embeddingsClient.createEmbeddings(request);

    assertThat(embeddings.data())
        .hasSize(1)
        .allSatisfy(embedding -> assertThat(embedding.embedding()).isNotEmpty());
  }

  @Test
  public void testFilesClient() {
    FilesClient filesClient = chatJPT.filesClient();

    Path jsonlFile = getTestResource("/mydata.jsonl");

    UploadFileRequest uploadFileRequest =
        UploadFileRequest.newBuilder().file(jsonlFile).purpose("fine-tune").build();

    File uploadedFile = filesClient.uploadFile(uploadFileRequest);

    List<File> uploadedFiles = filesClient.listFiles();

    assertThat(uploadedFiles).contains(uploadedFile);

    File retrievedFile = filesClient.retrieveFile(uploadedFile.id());

    assertThat(retrievedFile).isEqualTo(uploadedFile);
  }

  @Test
  @Disabled("Fine-tuning models are costly")
  public void testFineTuningClient() {
    FilesClient filesClient = chatJPT.filesClient();
    FineTuningClient fineTuningClient = chatJPT.fineTuningClient();

    Path trainingFile = getTestResource("/mydata.jsonl");
    UploadFileRequest uploadFileRequest =
        UploadFileRequest.newBuilder().file(trainingFile).purpose("fine-tune").build();
    File uploadedTrainingFile = filesClient.uploadFile(uploadFileRequest);

    CreateFineTuningJobRequest createFineTuningJobRequest =
        CreateFineTuningJobRequest.newBuilder()
            .trainingFile(uploadedTrainingFile.id())
            .model("gpt-3.5-turbo")
            .build();

    FineTuningJob createdFineTuningJob =
        fineTuningClient.createFineTuningJob(createFineTuningJobRequest);

    assertThat(createdFineTuningJob).isNotNull();

    PaginatedFineTuningJobs fineTuningJobs =
        fineTuningClient.listFineTuningJobs(Optional.empty(), Optional.empty());

    assertThat(fineTuningJobs.hasMore()).isFalse();
    assertThat(fineTuningJobs.data())
        .anySatisfy(
            fineTuningJob -> assertThat(fineTuningJob.id()).isEqualTo(createdFineTuningJob.id()));

    PaginatedFineTuningEvents fineTuningJobEvents =
        fineTuningClient.listFineTuningJobEvents(
            createdFineTuningJob.id(), Optional.empty(), Optional.empty());

    assertThat(fineTuningJobEvents.hasMore()).isFalse();
    assertThat(fineTuningJobEvents.data()).isNotEmpty();

    FineTuningJob retrievedFineTuningJob =
        fineTuningClient.retrieveFineTuningJob(createdFineTuningJob.id());

    assertThat(retrievedFineTuningJob).isNotNull();

    FineTuningJob cancelledFineTuningJob =
        fineTuningClient.cancelFineTuningJob(createdFineTuningJob.id());

    assertThat(cancelledFineTuningJob).isNotNull();
  }
}
