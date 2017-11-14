package ele.me.hackathon.tank;

import ele.me.hackathon.tank.player.PlayerServer;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransportException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

/**
 * Created by lanjiangang on 27/10/2017.
 */
public class GameEngine {
    private GameStateMachine stateMachine;
    private Player playerA;
    private Player playerB;
    private int maxRound;
    private String mapFile;
    private GameMap map;
    private int noOfTanks;
    private int tankSpeed;
    private int shellSpeed;
    private int tankHP;
    private int tankScore;
    private int flagScore;
    private int roundTimeout;
    private String playerAAddres;
    private String playerBAddres;
    private boolean flagGenerated = false;
    private int noOfFlagGenerated = 0;

    private Map<String, PlayerServer.Client> clients;
    private Map<String, Player> players;
    private GameOptions gameOptions;
    private Environment env = new Environment();

    public static class Environment {
        public String get(String name) {
            return System.getenv(name);
        }
    }

    public static void main(String[] args) throws TTransportException {

        GameEngine engine = new GameEngine();
        engine.parseArgs(args);
        engine.startGame();
    }

    public GameEngine() {
    }

     void parseArgs(String[] args) {
        mapFile = args[0];
        noOfTanks = Integer.parseInt(args[1]);
        tankSpeed = Integer.parseInt(args[2]);
        shellSpeed = Integer.parseInt(args[3]);
        tankHP = Integer.parseInt(args[4]);
        tankScore = Integer.parseInt(args[5]);
        flagScore = Integer.parseInt(args[6]);
        maxRound = Integer.parseInt(args[7]);
        roundTimeout = Integer.parseInt(args[8]);
        playerAAddres = args[9];
        playerBAddres = args[10];
        this.gameOptions = new GameOptions(noOfTanks, tankSpeed, shellSpeed, tankHP, tankScore, flagScore, maxRound, roundTimeout);
        System.out.println("Parameters parsed. " + this.toString());
    }

    private void startGame() throws TTransportException {
        initGameStateMachine();
        this.clients = connectToPlayers();
        play();
    }

    private void initGameStateMachine() {
        map = loadMap(mapFile);
        Map<Integer, Tank> tanks = generateTanks();
        this.players = assignTankToPlayers(tanks);

        stateMachine = new GameStateMachine(tanks, map);
        stateMachine.setOptions(gameOptions);
        stateMachine.setPlayers(players);
    }

    private Map<String, Player> assignTankToPlayers(Map<Integer, Tank> tanks) {
        Map<String, Player> players = new LinkedHashMap<>();

        players.put(playerAAddres,
                new Player(playerAAddres, tanks.keySet().stream().filter(id -> id <= noOfTanks).collect(Collectors.toCollection(LinkedList::new))));
        players.put(playerBAddres,
                new Player(playerBAddres, tanks.keySet().stream().filter(id -> id > noOfTanks).collect(Collectors.toCollection(LinkedList::new))));
        return players;
    }

    protected Map<Integer, Tank> generateTanks() {
        Map<Integer, Tank> tanks = new LinkedHashMap<>();
        int index = 0;
        int mapsize = map.size();
        int n = (int) Math.sqrt(gameOptions.getNoOfTanks());
        for (int x = 1; x < n + 1; x++) {
            for (int y = 1; y < n + 1; y++) {
                index++;
                tanks.put(index, new Tank(index, new Position(x, y), Direction.DOWN, tankSpeed, shellSpeed, tankHP));
                tanks.put(index + gameOptions.getNoOfTanks(),
                        new Tank(index + gameOptions.getNoOfTanks(), new Position(mapsize - x - 1, mapsize - y - 1), Direction.UP, tankSpeed, shellSpeed, tankHP));
            }
        }
        return tanks;
    }

    private void play() {
        List<PlayerInteract> actors = Arrays.asList(new String[] { playerAAddres, playerBAddres }).stream().map(name -> buildPlayerInteract(name, gameOptions))
                .collect(Collectors.toList());
        Map<String, LinkedBlockingQueue<List<TankOrder>>> tankOrderQueues = actors.stream()
                .collect(Collectors.toMap(PlayerInteract::getAddress, act -> act.getCommandQueue()));
        Map<String, LinkedBlockingQueue<GameState>> stateQueues = actors.stream()
                .collect(Collectors.toMap(PlayerInteract::getAddress, act -> act.getStatusQueue()));

        actors.forEach(act -> act.start());

        //send a singal tp upload map and tank list
        stateQueues.values().forEach(q -> q.offer(new GameState("fakeState")));
        int round = 0;
        for (; round < maxRound; round++) {
            System.out.println("Round " + round);
            //clear the command queue to prevent previous dirty command left in the queue
            tankOrderQueues.values().forEach(q -> q.clear());

            Map<String, GameState> latestState = stateMachine.reportState();
            latestState.entrySet().forEach(k -> stateQueues.get(k.getKey()).offer(k.getValue()));

            List<TankOrder> orders = new LinkedList<>();
            tankOrderQueues.values().forEach(q -> {
                try {
                    orders.addAll(q.take());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            });

            orders.forEach(o -> System.out.println(o));
            stateMachine.newOrders(orders);

            if (stateMachine.gameOvered()) {
                break;
            }

            checkGenerateFlag(round);
        }

        reportResult(round);
    }

    protected void reportResult(int round) {
        String resUrl = env.get("WAR_CALLBACK_URL");
        HttpPost post = new HttpPost(resUrl);
        StringBuffer sb = new StringBuffer("{\n");

        Map<String, Integer> scores;
        if (round < maxRound) {
            scores = stateMachine.countScore(tankScore, 0);
        } else {
            scores = stateMachine.countScore(tankScore, flagScore);
        }

        if (scores.get(playerAAddres) > scores.get(playerBAddres)) {
            System.out.println(playerAAddres + " wins the game!");
            sb.append("\"result\":\"draw\",\n");
            sb.append("\"win\":\"").append(playerAAddres).append("\"\n");
        } else if (scores.get(playerAAddres) == scores.get(playerBAddres)) {
            System.out.println("Draw game!");
            sb.append("\"result\":\"draw\",\n");
        } else {
            System.out.println(playerBAddres + " wins the game!");
            sb.append("\"result\":\"draw\",\n");
            sb.append("\"win\":\"").append(playerBAddres).append("\"\n");
        }
        sb.append("}");
        post.setEntity(new InputStreamEntity(new ByteArrayInputStream(sb.toString().getBytes())));
        CloseableHttpClient httpclient = HttpClients.createDefault();
        try {
            httpclient.execute(post);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    protected void checkGenerateFlag(int round) {
        if (flagGenerated == false) {
            //generate if has past half rounds and no tank is lost
            if (round > (gameOptions.getMaxRound() / 2 - 1) && stateMachine.getTankList().size() == 2 * gameOptions.getNoOfTanks()) {
                System.out.println("Start to generate flag.");
                flagGenerated = true;
                stateMachine.generateFlag();
                noOfFlagGenerated++;
            }
        } else {
            //after first time, generate the flag repeatly but no more than one player's number of tanks
            if ((round - gameOptions.getMaxRound() / 2) % (gameOptions.getMaxRound() / 2 / gameOptions.getNoOfTanks() + 1) == 0) {
                stateMachine.generateFlag();
                noOfFlagGenerated++;
            }
        }

    }

    private PlayerInteract buildPlayerInteract(String name, GameOptions gameOptions) {
        return new PlayerInteract(name, clients.get(name), map, players.get(name).getTanks(), this.gameOptions);
    }

    private Map<String, PlayerServer.Client> connectToPlayers() throws TTransportException {
        Map<String, PlayerServer.Client> clients = new LinkedHashMap<>();
        clients.put(playerAAddres, buildPlayerConnection(playerAAddres));
        clients.put(playerBAddres, buildPlayerConnection(playerBAddres));
        return clients;
    }

    private PlayerServer.Client buildPlayerConnection(String addr) throws TTransportException {
        String host = addr.split(":")[0];
        int port = Integer.parseInt(addr.split(":")[1]);
        TSocket transport = new TSocket(host, port);
        transport.open();
        transport.setTimeout(roundTimeout);
        TProtocol protocol = new TBinaryProtocol(transport);
        PlayerServer.Client client = new PlayerServer.Client(protocol);
        return client;
    }

    private GameMap loadMap(String fileName) {
        try {
            return GameMap.load(new FileInputStream(new File(fileName)));
        } catch (IOException e) {
            throw new RuntimeException("failed to load map file : " + fileName);
        }
    }

    public void setStateMachine(GameStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    public GameOptions getGameOptions() {
        return gameOptions;
    }

    public int getNoOfFlagGenerated() {
        return noOfFlagGenerated;
    }

    public void setMap(GameMap map) {
        this.map = map;
    }

    public void setEnv(Environment env) {
        this.env = env;
    }
}
