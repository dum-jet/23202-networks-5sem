import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Server {
    private static final int MAX_FILENAME_SIZE = 4096;
    private static final long MAX_FILE_SIZE = 1L << 40;
    private static final String UPLOAD_DIR = "uploads";

    private final int port;
    private final ExecutorService threadPool = Executors.newCachedThreadPool();

    private int connectionsCount = 0;

    public Server(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        Path uploadPath = Paths.get(UPLOAD_DIR);
        if (!Files.exists(uploadPath))
            Files.createDirectory(uploadPath);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Сервер запущен на порту " + port);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                ++connectionsCount;
                threadPool.submit(new ClientHandler(clientSocket, connectionsCount));
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }


    private static class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private final int id;
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        private long startTime = 0;
        private final int PERIOD = 3;
        private final AtomicLong prevBytes = new AtomicLong(0);
        private final AtomicLong currentBytes = new AtomicLong(0);

        public ClientHandler(Socket socket, int id) {
            this.clientSocket = socket;
            this.id = id;
        }

        private void printStatistics() { // bytes per second
            long currBytes = currentBytes.get();
            double instantSpeed = (currBytes - prevBytes.get()) / (double)PERIOD;
            prevBytes.set(currBytes);
            double avgSpeed = currBytes / (Math.max((System.nanoTime() - startTime), 1) / 1_000_000_000.0);
            System.out.printf("Клиент: %d, мгновенная скорость: %f, средняя за сеанс: %f, байт получено: %d %n", id, instantSpeed, avgSpeed, currBytes);
        }


        @Override
        public void run() {
            String clientAddr = clientSocket.getRemoteSocketAddress().toString();
            System.out.println("\nСоединение с: " + clientAddr);
            
            DataInputStream in = null;
            DataOutputStream out = null;
            try {
                in = new DataInputStream(clientSocket.getInputStream());
                out = new DataOutputStream(clientSocket.getOutputStream());

                // длина имени файла
                int filenameLength = in.readInt();
                if (filenameLength > MAX_FILENAME_SIZE || filenameLength < 1) {
                    System.out.println("Некорректная длина имени файла: " + filenameLength);
                    return;
                }

                // полученное имя файла (путь)
                byte[] filenameBytes = new byte[filenameLength];
                in.readFully(filenameBytes);
                String receivedFilename = new String(filenameBytes, StandardCharsets.UTF_8);

                // проверка пути (не вне uploads после нормализации)
                Path uploadsDirPath = Paths.get(UPLOAD_DIR).toAbsolutePath().normalize();
                Path targetPath = uploadsDirPath.resolve(receivedFilename).normalize();
                if (!targetPath.startsWith(uploadsDirPath)) {
                    System.out.println("Попытка записи вне директории uploads: " + receivedFilename);
                    return;
                }

                // создание директорий
                Path parentDir = targetPath.getParent();
                if (parentDir != null && !Files.exists(parentDir)) {
                    Files.createDirectories(parentDir);
                    System.out.println("Созданы директории: " + parentDir);
                }

                // проверка имени файла
                Path filename = targetPath.getFileName();
                if (filename == null || filename.toString().trim().isEmpty() || Files.exists(targetPath)) {
                    filename = Paths.get("newfile" + id);
                    assert parentDir != null;
                    targetPath = parentDir.resolve(filename);
                    System.out.println("Некорректное имя, присвоено новое: " + targetPath);
                }



                // размер файла
                long fileSize = in.readLong();
                if (fileSize > MAX_FILE_SIZE || fileSize < 0) {
                    System.out.println("Некорректный размер файла: " + fileSize);
                    return;
                }

                scheduler.scheduleAtFixedRate(this::printStatistics, PERIOD, PERIOD, TimeUnit.SECONDS);

                long totalBytesRead = 0;
                startTime = System.nanoTime();
                try (OutputStream fos = Files.newOutputStream(targetPath, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;


                    while (totalBytesRead < fileSize && (bytesRead = in.read(buffer, 0, (int) Math.min(buffer.length, fileSize - totalBytesRead))) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        totalBytesRead = currentBytes.addAndGet(bytesRead);
                    }
                } finally {
                    scheduler.shutdownNow();
                    double totalElapsedSeconds = Math.max((System.nanoTime() - startTime), 1) / 1_000_000_000.0;
                    System.out.printf("Клиент %d, загружено %d байт за %f секунд, средняя скорость: %f %n",
                            id, totalBytesRead, totalElapsedSeconds, totalBytesRead / totalElapsedSeconds);

                    if (totalBytesRead == fileSize) {
                        out.writeByte(1);
                        System.out.println("Файл " + receivedFilename + " успешно принят от " + clientSocket.getRemoteSocketAddress());
                    } else {
                        out.writeByte(0);
                        System.out.println("Ошибка: ожидаемый размер " + fileSize + ", получено " + currentBytes.get());
                    }
                }
            } catch (IOException e) {
                System.out.println("Ошибка при обработке клиента " + clientSocket.getRemoteSocketAddress() + ": " + e.getMessage());
                try {
                    if (out != null)
                        out.writeByte(0);
                } catch (IOException ee) {
                    ;
                }
            } finally {
                try {
                    if (in != null) in.close();
                    if (out != null) out.close();
                    clientSocket.close();
                } catch (IOException e) {
                    System.out.println("Ошибка при закрытии соединения: " + e.getMessage());
                }
            }
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Использование: java Server {порт}");
            return;
        }
        int port = Integer.parseInt(args[0]);
        Server server = new Server(port);
        try {
            server.start();
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }


}