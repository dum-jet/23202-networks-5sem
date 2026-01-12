import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

public class Main {
    public static void main(String[] args) {
        System.out.println("Введите название места: ");
        Scanner scanner = new Scanner(System.in);
        GeoInfoApp app = new GeoInfoApp(scanner.nextLine());
        try {
            CompletableFuture<String> future = app.startChain();
            System.out.println(future.get());
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }
}