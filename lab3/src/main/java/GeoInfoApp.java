import java.util.concurrent.CompletableFuture;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletionException;

import org.json.JSONArray;
import org.json.JSONObject;

public class GeoInfoApp {
    private final String locationName;
    private static final int MAX_PLACES_OF_INTEREST = 10;

    private static final String GRAPH_HOPPER_API_KEY = "3bc92e19-116d-4210-bac1-68ba6500c246";
    private static final String OPEN_WEATHER_API_KEY = "a254f3a146b094c217971baeabf1fdc2";
    private static final String OPEN_TRIP_MAP_API_KEY = "5ae2e3f221c38a28845f05b652e5c598f66ba19252f4e43b6d506015";

    public GeoInfoApp(String input) {
        this.locationName = input.replace(" ", "_");
    }
    
    public CompletableFuture<String> startChain() {
        return CompletableFuture.supplyAsync(this::getLocationPoint).thenCompose(this::getWeatherAndPlacesAsync);
    }
    
    private CompletableFuture<String> getWeatherAndPlacesAsync(Point point) {
        CompletableFuture<String> weatherFuture = getWeatherAsync(point);
        CompletableFuture<String> placesFuture = getPlacesAsync(point);
        return weatherFuture.thenCombine(placesFuture, this::printSummary);
    }

    private String printSummary(String weather, String places) {
        return weather + "\n" + places;
    }

    public String constructWeatherURL(Point point) {
        return String.format(Locale.US, "https://api.openweathermap.org/data/2.5/weather?lat=%f&lon=%f&units=metric&appid=%s", point.lat, point.lon, OPEN_WEATHER_API_KEY);
    }

    public String constructPlacesOfInterestURL(Point point) {
        return String.format(Locale.US, "http://api.opentripmap.com/0.1/en/places/radius?radius=2000&lon=%f&lat=%f&format=json&lang=en&rate=1&limit=%d&apikey=%s", point.lon, point.lat, MAX_PLACES_OF_INTEREST, OPEN_TRIP_MAP_API_KEY);
    }

    public String constructPlaceDescriptionURL(String xid) throws IOException {
        return String.format(Locale.US, "http://api.opentripmap.com/0.1/ru/places/xid/%s?apikey=%s", xid, OPEN_TRIP_MAP_API_KEY);
    }

    public String constructLocationURL(String locationName) {
        return String.format(Locale.US, "https://graphhopper.com/api/1/geocode?q=%s&locale=en&key=%s", locationName, GRAPH_HOPPER_API_KEY);
    }
    
    private Point getLocationPoint() {
        try {
            String data = fetchFromURL(constructLocationURL(locationName));
            JSONObject jsonObject = new JSONObject(data);
            JSONArray hits = jsonObject.getJSONArray("hits");
            if (hits.isEmpty())
                throw new CompletionException(new RuntimeException("No results"));
            for (int i = 0; i < hits.length(); ++i) {
                JSONObject hit = hits.getJSONObject(i);
                System.out.printf("%d) %s - %s (%s, %s)%n", i + 1,
                        hit.getString("country"),
                        hit.getString("name"),
                        hit.getString("osm_key"),
                        hit.getString("osm_value"));
            }
            Scanner scanner = new Scanner(System.in);
            int usersChoice = scanner.nextInt();
            if (usersChoice < 1 || usersChoice > hits.length())
                throw new CompletionException(new RuntimeException("Out of bounds"));

            JSONObject chosenPoint = hits.getJSONObject(usersChoice - 1).getJSONObject("point");
            double lng = chosenPoint.getDouble("lng");
            double lat = chosenPoint.getDouble("lat");
            System.out.printf("Chosen location longitude: %f, latitude: %f %n", lng, lat);
            return new Point(lng, lat);
        } catch (Exception e) {
            System.out.println(e.getMessage());
            throw new CompletionException(new RuntimeException("No results"));
        }
    }

    private CompletableFuture<String> getWeatherAsync(Point point) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String data = fetchFromURL(constructWeatherURL(point));
                JSONObject jsonObject = new JSONObject(data);
                JSONArray weather = jsonObject.getJSONArray("weather");
                JSONObject main = jsonObject.getJSONObject("main");
                return String.format("Weather: %s %nTemp: %s℃ %nFeels like: %s℃ %nPressure: %d %nHumidity: %d %nWind speed: %f %n",
                        weather.getJSONObject(0).getString("main"),
                        main.getDouble("temp"),
                        main.getDouble("feels_like"),
                        main.getInt("pressure"),
                        main.getInt("humidity"),
                        jsonObject.getJSONObject("wind").getDouble("speed"));
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }

    private static <String> CompletableFuture<List<String>> sequence(List<CompletableFuture<String>> futures) {
        CompletableFuture<Void> allDone = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        return allDone.thenApply(v -> futures.stream().map(CompletableFuture::join).toList());
    }

    private CompletableFuture<String> getPlacesAsync(Point point) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String data = fetchFromURL(constructPlacesOfInterestURL(point));
                if ("{}".equals(data.trim()))
                    return new JSONArray();
                return new JSONArray(data);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }).thenCompose(placesArray -> {
            if (placesArray.isEmpty())
                return CompletableFuture.completedFuture("No places of interest found nearby\n");

            List<CompletableFuture<String>> detailFutures = new ArrayList<>();
            for (int i = 0; i < placesArray.length(); ++i) {
                String xid = placesArray.getJSONObject(i).getString("xid");
                CompletableFuture<String> detail = CompletableFuture.supplyAsync(() -> {
                    try {
                        return fetchFromURL(constructPlaceDescriptionURL(xid));
                    } catch (IOException e) {
                        throw new CompletionException(new RuntimeException("Failed to fetch details for: " + xid, e));
                    }
                });
                detailFutures.add(detail);
            }

            return sequence(detailFutures)
                    .thenApply(descriptions -> {
                        StringBuilder sb = new StringBuilder();
                        for (String json : descriptions) {
                            JSONObject printableDesc = new JSONObject(json);
                            sb.append("Name: ").append(printableDesc.getString("name")).append('\n')
                                    .append("Kinds: ").append(printableDesc.getString("kinds")).append('\n');
                            if (!printableDesc.isNull("wikipedia_extracts")) {
                                sb.append("Description: ")
                                        .append(printableDesc.getJSONObject("wikipedia_extracts").getString("text"))
                                        .append('\n');
                            }
                            sb.append('\n');
                        }
                        return sb.toString();
                    });
        });
    }
    
    private static String fetchFromURL(String URLString) throws IOException {
        StringBuilder sb = new StringBuilder();
        URL url = new URL(URLString);
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        connection.setRequestMethod("GET");

        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String line;
        while ((line = in.readLine()) != null)
            sb.append(line);
        in.close();
        return sb.toString();
    }
}