package game;

import com.google.gson.Gson;
import org.jspace.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/*
General template: (playerId, "command", payload)

SERVER TO CLIENT COMMANDS
- (playerId, "allReady"): All players are ready to play
- (playerId, "take", status): The player can take his turn, status tell if the game is done (winnerId or "alive")
- (playerId, "players", String[]): A list of all the players in the correct game order
- (playerId, "takes", newPlayer): newPlayer (id) has begun his turn
- (playerId, "cards", Card[]): An array of card is ready for the player to draw
- (playerId, "invalid"): The played card was invalid
- (playerId, "success": The request was successful
- (playerId, "board", Board): The board was updated
- (playerId, "UNO", receiver, caller): A missing UNO was applied from receiver to caller
- (playerId, "UNO", caller): The caller successfully called UNO


CLIENT TO SERVER COMMANDS
- (playerId, "ready"): The player is ready to begin the game
- (playerId, "ended"): The players ends his turn
- (playerId, "taken"): The players takes his turn
- (playerId, "action", Action): The player performs an action (play or draw card)
- (playerId, "UNO"): The player call UNO
- (playerId, "missingUNO"): A player calls missing UNO on someone else
 */

// Given a game-space this class handles it for the players
public class GameHandler {

    SpaceRepository gameRepository; // The repository through which the players communicate
    SequentialSpace gameSpace; // The space through which the players communicate

    int currentPlayer = 0;  // The index of the current player to take turn
    int previousPlayer;  // The index of the last player to take turn
    boolean missingUNO = false;  // True if the last player forgot to say UNO until the next player takes action
    boolean UNO = false;  // True if the player says UNO
    Map<String, ArrayList<Card>> hands = new HashMap<>();  // To keep track of what cards each player has on his hand

    String[] playerIds;  // List of all the player's ids
    RandomSpace deck = new RandomSpace();  // The deck from which the players can draw cards
    StackSpace stack = new StackSpace();  // The stack in which the players place their cards. Top card is available with a queryp
    boolean reverse = false;  // True if the order in which the players take turn should be reversed
    boolean skipNextPlayer = false;  // True if a skip card has been played and the next player should be skipped
    int penalty = 0;  // The amount of penalty the next player is going to receive
    boolean turnDone = false;  // A player only gets one action per turn (draw or play a card)
    boolean gameDone = false;  // False until a winner is found

    // Constructor
    public GameHandler(SpaceRepository gameRepository, SequentialSpace gameSpace, String[] playerIds) throws InterruptedException {

        this.playerIds = playerIds;
        this.gameSpace = gameSpace;
        this.gameRepository = gameRepository;

        // Needed before manipulating shared variables
        gameSpace.put("lock");

        // Build the board
        initBoard();

        // Start the game
        initGame();

        // Start listening
        listen();
    }

    // Results in the deck being filled with tuples like ("red", "3")
    private void initBoard() throws InterruptedException {
        int numberOfCards = 52;

        String[] colors = {
                "Red", "Red", "Red", "Red", "Red", "Red", "Red", "Red", "Red", "Red", "Red", "Red",
                "Yellow", "Yellow", "Yellow", "Yellow", "Yellow", "Yellow", "Yellow", "Yellow", "Yellow", "Yellow", "Yellow", "Yellow",
                "Blue", "Blue", "Blue", "Blue", "Blue", "Blue", "Blue", "Blue", "Blue", "Blue", "Blue", "Blue",
                "Green", "Green", "Green", "Green", "Green", "Green", "Green", "Green", "Green", "Green", "Green", "Green",
                "Black", "Black", "Black", "Black"
        };

        String[] values = {
                "1", "2", "3", "4", "5", "6", "7", "8", "9", "Skip", "Draw", "Reverse",
                "1", "2", "3", "4", "5", "6", "7", "8", "9", "Skip", "Draw", "Reverse",
                "1", "2", "3", "4", "5", "6", "7", "8", "9", "Skip", "Draw", "Reverse",
                "1", "2", "3", "4", "5", "6", "7", "8", "9", "Skip", "Draw", "Reverse",
                "Color", "Color", "Draw", "Draw"
        };

        assert colors.length == numberOfCards;
        assert values.length == numberOfCards;

        for (int i = 0; i < 52; i++) {
            deck.put(new Card(colors[i], values[i]));
        }

        // Flip the first card to the stack
        stack.put(getRandomCardFromDeck());
    }

    // Initialize the game
    private void initGame() throws InterruptedException {

        // Provide players with cards
        for (int i = 0; i < playerIds.length; i++) {
            hands.put(playerIds[i], new ArrayList<>());
            givePlayerCards(playerIds[i], 7);
        }

        // Send the board to all the players to display
        sendBoard();

        // Send the player list to all the players
        sendPlayerList();

        // Wait for all players to be ready
        for (int i = 0; i < playerIds.length; i++) {
            gameSpace.get(new FormalField(String.class), new ActualField("ready"));
        }

        // Notify players everyone is ready
        for (int i = 0; i < playerIds.length; i++) {
            gameSpace.put(playerIds[i], "allReady");
        }

        // Notify first player to start
        gameSpace.put(playerIds[currentPlayer], "take", "alive");

        startUnoThreads();
    }

    private void startUnoThreads() {

        // Start to listen for UNO
        Thread unoThread = new Thread() {
            public void run() {
                while (!gameDone) {
                    try {
                        checkUno();
                    } catch (InterruptedException e) { e.printStackTrace(); }
                }
            }
        };

        unoThread.start();

        // Start to listen for missing UNO
        Thread missingUnoThread = new Thread() {
            public void run() {
                while (!gameDone) {
                    try {
                        checkMissingUno();
                    } catch (InterruptedException e) { e.printStackTrace(); }
                }
            }
        };

        missingUnoThread.start();
    }

    private void sendPlayerList() throws InterruptedException {
        for (int i = 0; i < playerIds.length; i++) {
            gameSpace.put(playerIds[i], "players", playerIds);
        }
    }

    private void listen() throws InterruptedException {

        while(true){
            takeTurn();

            // Listen for input action
            while (true)
                if(takeAction()) break;

            // Check if game is done
            if (isGameDone()) break;

            nextPlayer();
        }

        gameDone = true;
    }

    private boolean isGameDone() throws InterruptedException {

        // Check all players' hand
        for (Map.Entry<String, ArrayList<Card>> entry : hands.entrySet()) {

            // If a player has won end the game and notify
            if (entry.getValue().size() == 0) {
                for (int i = 0; i < playerIds.length; i++) {
                    gameSpace.put(playerIds[i], "take", entry.getKey());
                }
                return true;
            }
        }

        return false;
    }

    // Take turn (apply penalty)
    private void takeTurn() throws InterruptedException {

        // Wait for player to take turn
        String playerId = (String) gameSpace.get(
                new FormalField(String.class),
                new ActualField("taken"),
                new ActualField("!")
        )[0];

        // Check playerId
        if (!isCurrentPlayer(playerId)) return;

        // Notify other players who took turn
        for (int i = 0; i < playerIds.length; i++) {
            gameSpace.put(playerIds[i], "takes", playerId);
        }
        System.out.printf("Player:%s ",playerId);

        mutualExclusion(() -> {
            // Apply penalty if any
            if (penalty > 0) {
                givePlayerCards(playerId, penalty);
                penalty = 0;

                sendBoard();
            }
        });

    }

    // Allow a player to take actions (draw or play a card)
    // Returns true with success
    private boolean takeAction() throws InterruptedException {

        boolean success = false;

        // Listen for action
        Object[] request = gameSpace.get(
                new FormalField(String.class),
                new ActualField("action"),
                new FormalField(String.class)  // was new FormalField(Action.class)
        );
        Gson gson = new Gson();

        String playerId = (String) request[0];
        Action action = gson.fromJson((String) request[2],Action.class); // was Action action = (Action) request[2];

        System.out.println("action acitvated " +action.getAction());

        // The current player can only do one action per turn and only
        if (turnDone || !isCurrentPlayer(playerId)) {
            gameSpace.put(playerId, "invalid");
            return false;
        };

        // Make sure missing UNO is false before continuing to avoid race conditions
        mutualExclusion(() -> {
            missingUNO = false;
        });

        // If a card was played
        if (action.getAction().equals(Actions.PLAY))
            success = playACard(playerId, action.getCard());

        // If the player chose to draw a card
        if (action.getAction().equals(Actions.DRAW))
            success = drawACard(playerId);

        if (success) {
            // Disable possibility for more actions
            turnDone = true;
            return true;
        }

        return false;
    }

    // Notify next player (increment currentPlayer and previousPlayer)
    private void nextPlayer() throws InterruptedException {

        // Wait for the current player to end his turn
        String playerId = (String) gameSpace.get(new FormalField(String.class), new ActualField("ended"))[0];

        mutualExclusion(() -> {
            // Check for missing UNO
            if (hands.get(playerIds[currentPlayer]).size() == 1 && UNO == false)
                missingUNO = true;

            // Reset UNO
            UNO = false;

            // Check playerId
            if (!isCurrentPlayer(playerId)) return;

            // Update previous player
            previousPlayer = currentPlayer;

            // Update current player
            int increment = 1;
            if (skipNextPlayer) {
                increment++;
                skipNextPlayer = false;
            }

            if (!reverse) currentPlayer = (currentPlayer + increment) % playerIds.length;
            if (reverse) currentPlayer = ((currentPlayer - increment) + playerIds.length) % playerIds.length;

            // Enable move for next player
            turnDone = false;
        });

        // Notify next player to start
        gameSpace.put(playerIds[currentPlayer], "take", "alive");
    }

    // Play a card (disable UNO, save penalty, respond with status)
    // Returns true with success
    private boolean playACard(String playerId, Card card) throws InterruptedException {

        // Check the move is valid
        if (!isMoveValid(card) || !isPlayersCard(playerId, card)) {
            gameSpace.put(playerId, "invalid");
            return false;
        }

        // Add the card to the stack
        stack.put(card);

        // Remove the card from the players hand
        removeCardFromPlayer(playerId, card);

        // If reverse
        if (card.value.equals("Reverse")) reverse = !reverse;

        // If skip
        if (card.value.equals("Skip")) skipNextPlayer = true;

        // If penalty
        if (card.value.equals("Draw")) {
            if (card.color.equals("Black")) penalty = 4;
            else penalty = 2;
        }

        // Notify players of change in board if game is not done
        if (!isGameDone())
            sendBoard();

        // Respond with success
        gameSpace.put(playerId, "success");

        return true;
    }

    // Removes a card from a player's hand
    private void removeCardFromPlayer(String playerId, Card card) throws InterruptedException {
        ArrayList<Card> playerHand = hands.get(playerId);

        // Loop through cards on player's hand to find match
        for (int i = 0; i < playerHand.size(); i++) {
            Card cardOnHand = playerHand.get(i);

            // If there is a match
            if (cardOnHand.equals(card)) {
                playerHand.remove(i);
                return;
            }
        }
    }

    // Check whether a given card exists on a player's hand
    private boolean isPlayersCard(String playerId, Card claimCard) {
        ArrayList<Card> playerHand = hands.get(playerId);

        // Loop through cards on player's hand to search for match
        for (int i = 0; i < playerHand.size(); i++) {
            Card trueCard = playerHand.get(i);

            // If there is a match
            if (trueCard.equals(claimCard)) return true;
        }

        return false;
    }

    // Send the board to all the players
    private void sendBoard() throws InterruptedException {
        Board board = getBoard();
        Gson gson = new Gson();
        // Send the new board and the players' hand to everyone
        for (int i = 0; i < playerIds.length; i++) {
            gameSpace.put(playerIds[i], "board", gson.toJson(board)); // was gameSpace.put(playerIds[i], "board", board);
            gameSpace.put(playerIds[i], "cards", gson.toJson(arraylistToArray(hands.get(playerIds[i]))));
            System.out.printf("Player,cards,%s\n",gson.toJson(arraylistToArray(hands.get(playerIds[i]))));
            //gameSpace.put(playerIds[i], "cards", "Test");
        }
    }

    public Board getBoard() {

        Card topCard = getTopCard();

        // Count number of cards on each player's hand
        Map<String, Integer> handsCount = new HashMap<>();
        for (Map.Entry<String, ArrayList<Card>> entry : hands.entrySet())
            handsCount.put(entry.getKey(), entry.getValue().size());

        return new Board(topCard, handsCount);
    }

    // Allow a player to draw a random card from the deck
    // Returns true with success
    private boolean drawACard(String playerId) throws InterruptedException {
        Gson gson = new Gson();
        // Only allow a player to draw a card if the player has no valid moves
        if (playerHasMoves(playerId)) {
            gameSpace.put(playerId, "invalid");
            return false;
        }

        Card card = getRandomCardFromDeck();

        // Send card to player
        gameSpace.put(playerId, "card", gson.toJson(card)); // was gameSpace.put(playerId, "card", card);

        // Add card to player's hand
        hands.get(playerId).add(card);

        // Notify other players of change
        sendBoard();

        // Respond with success
        gameSpace.put(playerId, "success");
        return true;
    }

    private boolean playerHasMoves(String playerId) {
        ArrayList<Card> hand = hands.get(playerId);

        // Check for valid moves
        for (int i = 0; i < hand.size(); i++) {
            if (isMoveValid(hand.get(i)))
                return true;
        }

        return false;
    }

    // Assigns a certain amount of random cards to a player drawn from the deck
    private void givePlayerCards(String playerId, int numberOfCards) throws InterruptedException {
        Card[] cards = new Card[numberOfCards];

        for (int i = 0; i < numberOfCards; i++) {
            Card card = getRandomCardFromDeck();
            cards[i] = card;
            hands.get(playerId).add(card);
        }
    }

    private Card[] arraylistToArray(ArrayList<Card> list) {
        Card[] cards = new Card[list.size()];

        // Loop through cards
        for (int i = 0; i < list.size(); i++) {
            cards[i] = list.get(i);
        }

        return cards;
    }

    // Draw a random card from the deck
    private Card getRandomCardFromDeck() throws InterruptedException {

        // If the deck is empty flip the stack
        if (deck.size() < 1) flipTheStack();

        return (Card) deck.getp(new FormalField(Card.class))[0];
    }

    // Checks if a giving player ID is the one currently playing
    private boolean isCurrentPlayer(String playerId) {
        return playerId.equals(playerIds[currentPlayer]);
    }

    // Check is a certain move is valid
    private boolean isMoveValid(Card card) {

        Card topCard = getTopCard();

        // If the top card is black, the move is always valid
        if (topCard.color.equals("Black")) return true;

        // If the play card is black, the move is always valid
        if (card.color.equals("Black")) return true;

        // If the colors match, the move is valid
        if (card.color.equals(topCard.color)) return true;

        // If the values match, the move is valid
        if (card.value.equals(topCard.value)) return true;

        // Else the move is invalid
        return false;
    }

    private Card getTopCard() {
        return (Card) stack.queryp(new FormalField(Card.class))[0];
    }

    // If there are no more cards in the deck, the stack needs to be added back
    private void flipTheStack() throws InterruptedException {

        // Save the top card to keep in stack
        Card topCard = getTopCard();

        // Get the others cards from the stack and add them back to the deck
        while(stack.size() > 0) {
            Card card = (Card) stack.getp(new FormalField(Card.class))[0];
            deck.put(card);
        }

        stack.put(topCard);
    }

    // Allow a player to call UNO while playing
    private void checkUno() throws InterruptedException {

        String playerId = (String) gameSpace.get(
                new FormalField(String.class),
                new ActualField("UNO")
        )[0];

        mutualExclusion(() -> {
            // Check playerId
            if (!isCurrentPlayer(playerId)) return;

            // Check turn is done
            if (!turnDone) return;

            // Check UNO is not already called
            if (UNO) return;

            // Check there is indeed UNO
            if (!isUNO(playerId)) return;

            UNO = true;

            // Notify players UNO was called successfully
            for (int i = 0; i < playerIds.length; i++) {
                gameSpace.put(playerIds[i], "UNO", playerId);
            }
            System.out.printf("UNO called by: %s\n",playerId);
        });
    }

    private boolean isUNO(String playerId) {
        return hands.get(playerId).size() == 1;
    }

    private void checkMissingUno() throws InterruptedException {
        String playerId = (String) gameSpace.get(
                new FormalField(String.class),
                new ActualField("missingUNO")
        )[0];

        mutualExclusion(() -> {
            if (missingUNO) {

                // Punish previous players
                givePlayerCards(playerIds[previousPlayer], 1);

                // Reset missing UNO
                missingUNO = false;

                // Notify players
                for (int i = 0; i < playerIds.length; i++) {
                    gameSpace.put(
                            playerIds[i],
                            "UNO",
                            playerIds[previousPlayer],
                            playerId

                    );
                }
                System.out.printf("Missing UNO called by: %s on: %s\n",playerId, playerIds[previousPlayer]);

                TimeUnit.SECONDS.sleep(1);
                sendBoard();
            }
        });
    }

    // Only one at a time is allowed access to the gameSpace through mutualExclusion
    private void mutualExclusion(Callable callable) throws InterruptedException {
        gameSpace.get(new ActualField("lock"));
        callable.call();
        gameSpace.put("lock");
    }

    interface Callable {
        public void call() throws InterruptedException;
    }
}

// A template for a single card in the deck
class Card {
    String color;
    String value;

    public Card(String color, String value) {
        this.color = color;
        this.value = value;
    }

    public String getColor() { return color; }
    public String getValue() { return value; }

    public boolean equals(Card card) {
        return (this.color.equals(card.color) &&
                this.value.equals(card.value));
    }
}

// Actions a player can preform during a round
enum Actions { PLAY, DRAW }

// Used to communicate what action a player wants to preform
// Allows us to listen for a general action on a tuple space
// and later preform logic, rather than having to listen for
// two separate potential actions
class Action {
    private Actions action;
    private Card card;

    public Action(Actions action, Card card) {
        this.action = action;
        this.card = card;
    }
    public Action(){

    }

    public Actions getAction() { return action; }
    public Card getCard() { return card; }
}

class Board {
    Card topCard;
    Map<String, Integer> hands;

    public Board(Card topCard, Map<String, Integer> playerHands) {
        this.topCard = topCard;
        this.hands = playerHands;
    }

    public Card getTopCard() { return topCard; }
    public Map<String, Integer> getHands() { return hands; }

    public void setTopCard(Card topCard) { this.topCard = topCard; }
}

// Listen for request for instance variables and responds with them
class Debug implements Runnable {

    SequentialSpace debug;
    GameHandler handler;

    public Debug(SequentialSpace debug, GameHandler handler) {
        this.debug = debug;
        this.handler = handler;
    }

    @Override
    public void run() {
        try {
            while (true) {

                // Listen for request
                Object[] request = debug.get(
                        new FormalField(String.class),
                        new FormalField(String.class)
                );

                String playerId = (String) request[0];
                String command = (String) request[1];

                if (command.equals("board"))
                    debug.put(playerId, handler.getBoard());

                if (command.equals("hands")) {

                    // Convert handler.hands to traditional Array for jSpace
                    // Find the longest hand in hands
                    int record = 0;
                    for (Map.Entry<String, ArrayList<Card>> entry : handler.hands.entrySet()) {
                        if (entry.getValue().size() > 0)
                            record = entry.getValue().size();
                    }

                    // One row per player and enough columns for playerIds and all cards
                    String[][] hands = new String[handler.hands.size()][record + 1];

                    // Start rows with playerIds
                    for (int i = 0; i < handler.hands.size(); i++) {
                        hands[i][0] = handler.playerIds[i];
                    }

                    // Do the mapping
                    int counter = 0;
                    for (Map.Entry<String, ArrayList<Card>> entry : handler.hands.entrySet()) {
                        ArrayList<Card> hand = entry.getValue();
                        for (int i = 1; i < hand.size() + 1; i++) {
                            hands[counter][i] = hand.get(i - 1).getColor() + " " + hand.get(i - 1).getValue();
                        }
                        counter++;
                    }

                    // Send output
                    debug.put(playerId, hands);
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}

class Server {
    public static void main(String[] args) throws InterruptedException, IOException {
        SpaceRepository spaceRepository = new SpaceRepository();
        spaceRepository.addGate("tcp://localhost:31415/?keep");
        SequentialSpace gameSpace = new SequentialSpace();
        spaceRepository.add("gameId", gameSpace);

        new GameHandler(spaceRepository, gameSpace, new String[]{"Mark", "Talha", "Volkan"}); // , "Volkan", "Mikkel"
    }
}

