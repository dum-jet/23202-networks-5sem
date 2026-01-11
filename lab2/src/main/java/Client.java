import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Client {
    private static final int MAX_FILENAME_SIZE = 4096;
    private static final long MAX_FILE_SIZE = 1L << 40;

    private final String filePath;
    private final String serverAddress;
    private final int serverPort;

    public Client(String filePath, String serverAddress, int serverPort) {
        this.filePath = filePath;
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
    }

    public void sendFile() throws IOException {
        Path file = Paths.get(this.filePath);
        if (!Files.exists(file) || !Files.isReadable(file)) {
            System.out.println("Отправляемый файл недоступен: " + filePath);
            return;
        }

        long fileSize = Files.size(file);
        if (fileSize > MAX_FILE_SIZE) {
            System.out.println("Файл слишком большой");
            return;
        }

        String fileName = file.toString();
        byte[] fileNameBytes = fileName.getBytes(StandardCharsets.UTF_8);
        if (fileNameBytes.length > MAX_FILENAME_SIZE) {
            System.out.println("Имя файла слишком длинное");
            return;
        }

        Socket socket = new Socket(serverAddress, serverPort);
        DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
        DataInputStream dis = new DataInputStream(socket.getInputStream());

        try {
            // заголовок
            dos.writeInt(fileNameBytes.length);
            dos.write(fileNameBytes);
            dos.writeLong(fileSize);
            dos.flush();

            // файл
            try (FileInputStream fis = new FileInputStream(file.toFile())) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    dos.write(buffer, 0, bytesRead);
                }
            }
            dos.flush();

            int response = dis.readByte();
            if (response == 1) {
                System.out.println("Успешная передача файла.");
            } else {
                System.out.println("Ошибка при передаче файла.");
            }
        } finally {
            try {
                dis.close();
                dos.close();
                socket.close();
            } catch (IOException e) {
                System.out.println("Ошибка при закрытии соединения: " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("Использование: java Client {путь_к_файлу} {адрес_сервера} {порт}");
            return;
        }
        String filePath = args[0];
        String serverAddress = args[1];
        int serverPort = Integer.parseInt(args[2]);

        Client client = new Client(filePath, serverAddress, serverPort);
        try {
            client.sendFile();
        } catch (IOException e) {
            System.out.println("Ошибка при передаче файла: " + e.getMessage());
        }
    }
}