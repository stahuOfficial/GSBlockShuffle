package me.stahu.gsblockshuffle.event;

import me.stahu.gsblockshuffle.GSBlockShuffle;
import me.stahu.gsblockshuffle.team.TeamsManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.*;


public class GameStateManager {
    private final GSBlockShuffle plugin;
    private final YamlConfiguration settings;
    private final TeamsManager teamsManager;
    private int gameState; // 0 - not started, 1 - started
    public int roundsPerGame;
    private int roundsRemaining;
    public int secondsInRound;
    public int secondsInRoundBreak;
    private int secondsLeft;
    private int roundTickTask;
    private BossBar bossBar;
    public Map<String, ArrayList<String>> playerBlockMap;
    public HashSet<Player> playersWithFoundBlock = new HashSet<>();
    private int roundBreakTickTask;

    public boolean setGameState(int gameState) {
        if (this.gameState == gameState) {
            return false;
        }
        if (gameState == 0) {
            this.gameState = gameState;
            endGame();
            return true;
        }
        if (gameState == 1) {
            this.gameState = gameState;
            startGame();
            return true;
        }
        return false;
    }

    public int getGameState() {
        return gameState;
    }

    public int getRoundsRemaining() {
        return roundsRemaining;
    }

    public GameStateManager(YamlConfiguration settings, GSBlockShuffle plugin, TeamsManager teamsManager) {
        this.teamsManager = teamsManager;

        this.playerBlockMap = new HashMap<>();

        this.gameState = 0;

        this.settings = settings;
        this.plugin = plugin;

        this.roundsPerGame = settings.getInt("roundsPerGame");
        this.secondsInRound = settings.getInt("roundTime");
    }

    public void startGame() {
        // Adding players with no team to their own team
        teamsManager.handleRemainingPlayers();

        teamsManager.setUpScoreboard();

        roundsRemaining = roundsPerGame;
        newRound();
    }

    public void newRound() {
        Bukkit.getScheduler().cancelTask(this.roundBreakTickTask);

        assignRandomBlocks();

        bossBar = this.createBossBar();
        secondsLeft = secondsInRound;
        roundTickTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::roundTick, 0, 20);
    }

    private void roundTick() {
        if (secondsLeft-- <= 0) {
            endRound();
            return;
        }
        double progress = secondsLeft / (double) (secondsInRound);

        updateBossBar(progress);

        if (secondsLeft < 61) {
            pingPlayers(secondsLeft);
        }
    }

    public void endRound() {
        Bukkit.getScheduler().cancelTask(this.roundTickTask);
        int membersWithoutBlock;
        HashSet<Team> eliminatedTeams = new HashSet<>();
        boolean eliminateAfterRound = settings.getBoolean("eliminateAfterRound");
        boolean allPlayersRequiredForTeamWin = settings.getBoolean("allPlayersRequiredForTeamWin");

        // this part of code checks if players have found their blocks and eliminates teams if necessary
        for (Team team : teamsManager.teams) {
            membersWithoutBlock = 0;
            for (String playerName : team.getEntries()) {
                Player player = Bukkit.getPlayer(playerName);
                if (!playersWithFoundBlock.contains(player)) {
                    // you did not find your block
                    if (eliminateAfterRound) {
                        membersWithoutBlock++;
                        eliminatedTeams.add(team);
                    }
                }
            }
            if (allPlayersRequiredForTeamWin && membersWithoutBlock > 0) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.sendRawMessage(team.getName() + " eliminated!");
                }
                eliminatedTeams.add(team);
            }
        }
        // Play loss sound to players who didn't find their block
        for (Player player : teamsManager.getPlayersWithATeam()) {
            if (!playersWithFoundBlock.contains(player)) {
                playBlockFoundSound(player, false);
            }
        }

        // Cleanup
        playersWithFoundBlock.clear();
        playerBlockMap.clear();
        this.clearBossBars();
        this.eliminateTeams(eliminatedTeams);

        roundsRemaining--;

        if (eliminateAfterRound && teamsManager.teams.size() == 1) {
            endGame();
            return;
        }
        if (roundsRemaining == 0) {
            endGame();
            return;
        }
        roundBreak();
    }

    /**
     * Initiates the break between rounds.
     * This method sets the duration of the round break based on the "roundBreakTime" setting.
     * It then creates a new boss bar and schedules the roundBreakTick method to be called every tick (20 ticks = 1 second).
     */
    public void roundBreak() {
        secondsInRoundBreak = settings.getInt("roundBreakTime");
        secondsLeft = secondsInRoundBreak;
        bossBar = this.createBossBar();

        roundBreakTickTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::roundBreakTick, 0, 20);
    }

    /**
     * Handles the tick event during the break between rounds.
     * This method decreases the seconds left in the break by one each tick.
     * If the seconds left reach zero, the boss bar is removed and a new round is started.
     * If there is only one second left, a countdown sound is played.
     * The progress of the boss bar is updated based on the remaining time in the break.
     */
    private void roundBreakTick() {
        if (secondsLeft-- <= 0) {
            this.bossBar.removeAll();
            newRound();
            return;
        }

        if (secondsLeft == 1) {
            playRoundCountdownSound();
        }

        double progress = secondsLeft / (double) (secondsInRoundBreak);
        updateBreakBossBar(progress);
    }

    /**
     * Sends an end game message to all players.
     * This method constructs a message indicating the end of the game and the final scores of each team.
     * The message is then sent to all online players.
     * Note: The getTeamScore method is used to retrieve the score of each team.
     */
    public void endGame() {
        Bukkit.getScheduler().cancelTask(this.roundBreakTickTask);
        Bukkit.getScheduler().cancelTask(this.roundTickTask);

        sendEndGameMessageToAllPlayers();

        bossBar.removeAll();
        teamsManager.clearScoreboards();

        setGameState(0);
    }

    /**
     * Sends an end game message to all players.
     * This method constructs a message indicating the end of the game and the final scores of each team.
     * The message is then sent to all online players.
     * Note: The getTeamScore method is used to retrieve the score of each team.
     */
    private void sendEndGameMessageToAllPlayers() {
        StringBuilder endMessage = new StringBuilder("Game ended!\n " + "Final scores:");

        ArrayList<Team> sortedTeams = teamsManager.getSortedTeams();

        for (Team team : sortedTeams) {
            System.out.println(team.getName() + " " + teamsManager.getTeamScore(team));
        }

        for (int i = 0; (i < sortedTeams.size()) && i < 3; i++) {
            if (i == 0) {
                endMessage.append("\n ").append(ChatColor.GOLD).append(i + 1).append(ChatColor.WHITE).append(". ").append(sortedTeams.get(i).getName()).append(": ").append(teamsManager.getTeamScore(sortedTeams.get(i)));
            } else if (i == 1) {
                endMessage.append("\n ").append(ChatColor.GRAY).append(i + 1).append(ChatColor.WHITE).append(". ").append(sortedTeams.get(i).getName()).append(": ").append(teamsManager.getTeamScore(sortedTeams.get(i)));
            } else if (i == 2) {
                endMessage.append("\n ").append(ChatColor.RED).append(i + 1).append(ChatColor.WHITE).append(". ").append(sortedTeams.get(i).getName()).append(": ").append(teamsManager.getTeamScore(sortedTeams.get(i)));
            }
        }

        for (int i = 3; i < sortedTeams.size(); i++) {
            endMessage.append("\n ").append(ChatColor.DARK_GRAY).append(i + 1).append(ChatColor.WHITE).append(". ").append(sortedTeams.get(i).getName()).append(": ").append(teamsManager.getTeamScore(sortedTeams.get(i)));
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.sendMessage(player, endMessage.toString());
        }
    }

    /**
     * Assigns random blocks to players based on the block assignment mode.
     * The block assignment mode can be one of the following: "onePerPlayer", "onePerTeam", or "onePerRound".
     * If the block assignment mode is incorrectly set, an error message is printed and the game ends.
     * "onePerPlayer": each player in each team is assigned a random block.
     * "onePerTeam": each team is assigned a random block, and all players in the team are assigned the same block.
     * "onePerRound": a random block is assigned to all players in all teams.
     * After the block is assigned, the player is sent a message with the name of their block.
     */
    private void assignRandomBlocks() {
        String blockAssignmentMode = settings.getString("blockAssignmentMode");
        ArrayList<String> blockNames = null;
        String blockName;
        ArrayList<String> blockAssignmentModes = new ArrayList<>();
        blockAssignmentModes.add("onePerPlayer");
        blockAssignmentModes.add("onePerTeam");
        blockAssignmentModes.add("onePerRound");

        if (!blockAssignmentModes.contains(blockAssignmentMode)) {
            // TODO raise error
            System.out.println("Error: blockAssignmentMode incorrectly set to: " + blockAssignmentMode);
            endGame();
            return;
        }
//        if (Objects.equals(blockAssignmentMode, "onePerPlayer")) {
//            for (Team team : teams.values()) {
//                for (String playerName : team.getEntries()) {
//                    blockName = plugin.categoryTree.getRandomBlock(settings);
//                    assignBlockToPlayer(playerName, blockName);
//                    Player player = Bukkit.getPlayer(playerName);
//                    player.sendRawMessage("Your block is: " + blockName);
//                }
//            }
//            return;
//        }
//
//        if (Objects.equals(blockAssignmentMode, "onePerTeam")) {
//            for (Team team : teams.values()) {
//                blockName = plugin.categoryTree.getRandomBlock(settings);
//                for (String playerName : team.getEntries()) {
//                    assignBlockToPlayer(playerName, blockName);
//                    Player player = Bukkit.getPlayer(playerName);
//                    player.sendRawMessage("Your teams block is: " + blockName);
//                }
//            }
//            return;
//        }
//
//        if (Objects.equals(blockAssignmentMode, "onePerRound")) {
//            blockName = plugin.categoryTree.getRandomBlock(settings);
//            for (Team team : teams.values()) {
//                for (String playerName : team.getEntries()) {
//                    assignBlockToPlayer(playerName, blockName);
//                    Player player = Bukkit.getPlayer(playerName);
//                    player.sendRawMessage("The block is: " + blockName);
//                }
//            }
//            return;
//        }
        // TODO test
        if (Objects.equals(blockAssignmentMode, "onePerRound")) {
            blockNames = plugin.categoryTree.getRandomBlock(settings);
        }
        for (Team team : teamsManager.teams) {
            if (Objects.equals(blockAssignmentMode, "onePerTeam")) {
                blockNames = plugin.categoryTree.getRandomBlock(settings);
            }
            for (String playerName : team.getEntries()) {
                if (Objects.equals(blockAssignmentMode, "onePerPlayer")) {
                    blockNames = plugin.categoryTree.getRandomBlock(settings);
                }
                assignBlockToPlayer(playerName, blockNames);
                Player player = Bukkit.getPlayer(playerName);
                assert blockNames != null;
                blockName = blockNames.get(0);
                blockName = blockName.replaceAll("_", " ");
                // TODO capitalize first letter of each word
                assert player != null;
                plugin.sendMessage(player, "Your block is: " + ChatColor.GOLD + blockName);
            }
        }
    }

    /**
     * Assigns a block to a player.
     * This method adds the player's name and the list of block names to the playerBlockMap.
     *
     * @param playerName    The name of the player to whom the block will be assigned.
     * @param blockNameList The list of block names to be assigned to the player.
     */
    private void assignBlockToPlayer(String playerName, ArrayList<String> blockNameList) {
        playerBlockMap.put(playerName, blockNameList);
        System.out.println(playerName + " got " + blockNameList);
    }

    /**
     * Handles the event when a player has found a block.
     * This method retrieves the block assignment mode and various game settings from the configuration.
     * Depending on the block assignment mode, it updates the player's status, the team's score, and potentially ends the round or the game.
     *
     * @param player The player who has found a block.
     */
// TODO refactor
    public void playerFoundBlock(Player player) {
        String blockAssignmentMode = settings.getString("blockAssignmentMode");
        boolean firstToWin = settings.getBoolean("firstToWin");
        boolean allPlayersRequiredForTeamWin = settings.getBoolean("allPlayersRequiredForTeamWin");
        boolean teamScoreIncrementPerPlayer = settings.getBoolean("teamScoreIncrementPerPlayer");
        Team team = teamsManager.getPlayerTeam(player);
        playBlockFoundSound(player, true);

        playerBlockMap.remove(player.getName());
        playersWithFoundBlock.add(player);

        // if true just increment the teams score
        if (teamScoreIncrementPerPlayer) {
            teamsManager.incrementTeamScore(team);
        } else {
            // else check if teamscore already incremented
            for (String playerName : team.getEntries()) {
                Player p = Bukkit.getPlayer(playerName);
                if (playersWithFoundBlock.contains(p)) {
                    break;
                }
                teamsManager.incrementTeamScore(team);
            }
        }

        // if firstToWin endRound
        if (firstToWin) {
            // if not allPlayersRequiredForTeamWin set teammates block to found
            if (!allPlayersRequiredForTeamWin) {
                for (String playerName : team.getEntries()) {
                    playersWithFoundBlock.add(Bukkit.getPlayer(playerName));
                    playerBlockMap.remove(playerName);
                }
                endRound();
                return;
            }
            endRound();
        }// if no players left - endRound
        else if (playerBlockMap.isEmpty()) {
            endRound();
            return;
        }// if allPlayersRequiredForTeamWin - check if all players have found block, if so endRound
        else if (allPlayersRequiredForTeamWin) {
            for (String playerName : team.getEntries()) {
                if (!playersWithFoundBlock.contains(Bukkit.getPlayer(playerName))) {
                    return;
                }
            }
            for (String playerName : team.getEntries()) {
                playersWithFoundBlock.remove(Bukkit.getPlayer(playerName));
                playerBlockMap.remove(playerName);
            }
            return;
        }
    }

    /**
     * Eliminates the specified teams from the game.
     * This method sends a message to all online players notifying them of each eliminated team.
     * It also removes the eliminated teams from the list of active teams and unregisters them from the scoreboard.
     *
     * @param eliminatedTeams A HashSet of teams to be eliminated.
     */
    private void eliminateTeams(HashSet<Team> eliminatedTeams) {
        for (Team eliminatedTeam : eliminatedTeams) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                plugin.sendMessage(player, eliminatedTeam.getName() + " has been eliminated!");
            }
            for (Team team : eliminatedTeams) {
                teamsManager.teams.remove(team);
            }
            eliminatedTeam.unregister();
        }
    }

    /**
     * Clears the playerBlockMap, which is used to track the blocks assigned to each player.
     * This method is typically used at the end of a round or game to reset the block assignments for the next round or game.
     */
    private void clearPlayerBlocks() {
        playerBlockMap.clear();
    }

    /**
     * Creates a new boss bar with a default message, color, and style.
     * The boss bar is initially green and solid, with the message "Something might've failed.".
     * The boss bar is then added to all players who are part of a team.
     *
     * @return The newly created boss bar.
     */
    private BossBar createBossBar() {
        BossBar bossBar = Bukkit.createBossBar("Something might've failed.", BarColor.GREEN, BarStyle.SOLID);
        for (Player player : teamsManager.getPlayersWithATeam()) {
            bossBar.addPlayer(player);
        }
        return bossBar;
    }

    /**
     * Updates the boss bar's progress, color, and title based on the remaining time in the round.
     * The progress of the boss bar is set to the provided progress value.
     * The color of the boss bar changes from green to red as the time decreases.
     * The title of the boss bar displays the remaining time in seconds.
     *
     * @param progress The progress of the boss bar, represented as a double value between 0 and 1.
     */
    private void updateBossBar(double progress) {
        ChatColor timerColor;

        // TODO do this like a binary search
        if (progress < 0.1) {
            timerColor = ChatColor.DARK_RED;
        } else if (progress < 0.2) {
            bossBar.setColor(BarColor.RED);
            timerColor = ChatColor.RED;
        } else if (progress < 0.3) {
            timerColor = ChatColor.GOLD;
        } else if (progress < 0.5) {
            bossBar.setColor(BarColor.YELLOW);
            timerColor = ChatColor.YELLOW;
        } else if (progress < 0.75) {
            bossBar.setColor(BarColor.GREEN);
            timerColor = ChatColor.GREEN;
        } else {
            timerColor = ChatColor.DARK_GREEN;
            bossBar.setColor(BarColor.GREEN);
        }
        this.bossBar.setProgress(progress);
        this.bossBar.setTitle(ChatColor.WHITE + "Time left: " + timerColor + secondsLeft + ChatColor.WHITE + "s");
    }

    /**
     * Updates the boss bar during the break between rounds.
     * The progress of the boss bar is set to the provided progress value.
     * The color of the boss bar is set to blue.
     * The title of the boss bar is set to display the time left until the new block is assigned.
     *
     * @param progress The progress of the boss bar, represented as a double value between 0 and 1.
     */
    private void updateBreakBossBar(double progress) {
        this.bossBar.setProgress(progress);
        this.bossBar.setColor(BarColor.BLUE);
        this.bossBar.setTitle(ChatColor.WHITE + "New block in: " + ChatColor.DARK_AQUA + secondsLeft + ChatColor.WHITE + "s");
    }

    public void clearBossBars() {
        bossBar.removeAll();
    }

    /**
     * Sends a ping sound to all online players based on the remaining seconds in the round.
     * The ping sound is played at 60, 30, and 10 seconds remaining, given that rounds are longer than 120, 60, and 30 seconds, respectively.
     * Additionaly a clock ticking sound is played when there are less than 10 seconds remaining.
     * If the muteSounds setting is enabled, no sound will be played.
     *
     * @param secondsLeft The number of seconds remaining in the round.
     */
    private void pingPlayers(int secondsLeft) {
        boolean muteSounds = settings.getBoolean("muteSounds");
        if (muteSounds) {
            return;
        }
        if (secondsLeft == 60 && secondsInRound > 120) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                pingPlayerNTimes(player, 1, 4);
            }
        } else if (secondsLeft == 30 && secondsInRound > 60) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                pingPlayerNTimes(player, 2, 4);
            }
        } else if (secondsLeft == 10 && secondsInRound > 30) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                pingPlayerNTimes(player, 3, 4);
            }
        } else if (secondsLeft < 10 && secondsInRound > 30) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.playSound(player.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 0.5F, 1.2F);
            }
        }
    }

    /**
     * Plays a ping sound to a specific player a given number of times with a delay between each ping.
     * The ping sound is played 'n' times, where 'n' is provided as an argument.
     * The delay between each ping is also provided as an argument.
     * If the muteSounds setting is enabled, no sound will be played.
     *
     * @param player The player to whom the sound will be played.
     * @param n      The number of times the ping sound will be played.
     * @param delay  The delay (in ticks) between each ping sound.
     */
    private void pingPlayerNTimes(Player player, int n, long delay) {
        boolean muteSounds = settings.getBoolean("muteSounds");
        if (muteSounds) {
            return;
        }

        for (int i = 0; i < n; i++) {
            Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> playPingSound(player), i * delay);
        }
    }

    /**
     * Plays a ping sound to a specific player in the game.
     * The ping sound consists of two notes, an octave apart from each other.
     * If the muteSounds setting is enabled, no sound will be played.
     *
     * @param player The player to whom the sound will be played.
     */
    private void playPingSound(Player player) {
        boolean muteSounds = settings.getBoolean("muteSounds");
        if (muteSounds) {
            return;
        }

        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1, 0.5F);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1, 1F);
    }

    /**
     * Plays a sound to indicate that a player has found a block.
     * The sound played depends on whether the block was found or not.
     * If the muteSounds setting is enabled, no sound will be played.
     *
     * @param player     The player to whom the sound will be played.
     * @param blockFound A boolean indicating whether the block was found or not.
     */
    private void playBlockFoundSound(Player player, boolean blockFound) {
        boolean muteSounds = settings.getBoolean("muteSounds");
        if (muteSounds) {
            return;
        }

        if (blockFound) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1, 1.189207F);
            Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1, 1.781797F), 4);
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1, 1.781797F);
            Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1, 1.059463F), 3);
        }
    }

    /**
     * Plays a countdown sound to all players in the game.
     * The countdown sound is played in three steps with a delay between each step.
     * If the muteSounds setting is enabled, no sound will be played.
     */
    private void playRoundCountdownSound() {
        boolean muteSounds = settings.getBoolean("muteSounds");
        if (muteSounds) {
            return;
        }
        playSoundToAllPlayers(Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1, 0.629961F);
        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> playSoundToAllPlayers(Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1, 0.629961F), 20);
        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> playSoundToAllPlayers(Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1, 1.259921F), 40);
    }

    /**
     * Plays a specific sound to all players in the game, given the sound, volume, and pitch.
     * If the muteSounds setting is enabled, no sound will be played.
     *
     * @param sound  The sound to be played.
     * @param volume The volume of the sound.
     * @param pitch  The pitch of the sound.
     */
    private void playSoundToAllPlayers(Sound sound, int volume, float pitch) {
        boolean muteSounds = settings.getBoolean("muteSounds");
        if (muteSounds) {
            return;
        }

        for (Player player : teamsManager.getPlayersWithATeam()) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }
}
