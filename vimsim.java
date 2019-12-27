import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Scanner;

public class vimsim {
    public static void main(String[] args) {
        if (args.length != 5 && args.length != 7) {
            System.out.println("INCORRECT USAGE: ./vmsim –n <numframes> -a <opt|fifo|aging> [-r <refresh>] <tracefile>");
            System.exit(1);
        }

        int num_frames = Integer.parseInt(args[1]);
        String algorithm = args[3];
        String filename;
        int refresh = -1;

        if (args.length == 5) {
            filename = args[4];
        } else {
            refresh = Integer.parseInt(args[5]);
            filename = args[6];
        }

        File file = new File(filename);
        Scanner scanner = null;
        try {
            scanner = new Scanner(file);
        } catch (FileNotFoundException e) {
            System.out.println("ERROR - Could not open file for reading: " + filename);
            System.exit(1);
        }
        if (algorithm.toLowerCase().equals("fifo")) fifo(scanner, num_frames);
        else if (algorithm.toLowerCase().equals("opt")) opt(file, num_frames);
        else if (algorithm.toLowerCase().equals("aging")) aging(scanner, num_frames, refresh);
        else {
            System.out.println("ERROR - unknown algorithm: " + algorithm + ". Valid options are: fifo, opt, aging");
        }
        scanner.close();
    }

    private static void fifo(Scanner scanner, int num_frames) {
        int num_access = 0;
        int num_faults = 0;
        int num_writes = 0;

        LinkedList<String> frames = new LinkedList<>();
        Map<String, Boolean> dirty_map = new HashMap<>();       //Contains addresses that we need to write
        while (scanner.hasNextLine()) {
            String[] line_arr = scanner.nextLine().split(" ");
            char mode = line_arr[0].charAt(0);
            String addr = line_arr[1].substring(2, 7);

            num_access++;

            if (mode == 's') {
                dirty_map.putIfAbsent(addr, true);
            }

            assert (frames.size() <= num_frames);    //Sanity check
            if (!frames.contains(addr) && frames.size() < num_frames) { //Have room in frames, add it
                num_faults++;
                frames.add(addr);
            } else if (!frames.contains(addr) && frames.size() == num_frames) { //No room in frames. replace the head node and add on new value to the end
                num_faults++;
                if (dirty_map.containsKey(frames.getFirst())) {
                    num_writes++;
                    dirty_map.remove(frames.getFirst());
                }
                frames.poll();
                frames.add(addr);
            }
        }

        System.out.println("Algorithm: FIFO");
        System.out.println("Number of frames: " + num_frames);
        System.out.println("Total memory accesses: " + num_access);
        System.out.println("Total page faults: " + num_faults);
        System.out.println("Total writes to disk: " + num_writes);

    }

    private static void opt(File file, int num_frames) {
        int num_access = 0;
        int num_faults = 0;
        int num_writes = 0;

        Map<String, Boolean> dirty_map = new HashMap<>();
        HashMap<String, LinkedList<Integer>> addresses_map = createAddressMap(file);    //Read in the entire file and populate a hashmap with the address and indexes at which it was read

        Scanner scanner = null;
        try {
            scanner = new Scanner(file);
        } catch (FileNotFoundException e) {
            System.out.println("ERROR - Could not open file for reading: " + file.getName());
            System.exit(1);
        }

        LinkedList<String> frames = new LinkedList<>();
        while (scanner.hasNextLine()) {
            String[] line_arr = scanner.nextLine().split(" ");
            char mode = line_arr[0].charAt(0);
            String addr = line_arr[1].substring(2, 7);

            num_access++;

            if (mode == 's') {
                dirty_map.putIfAbsent(addr, true);
            }
            //Update addresses_map as we read in addresses
            if (addresses_map.get(addr).size() > 1) {
                addresses_map.get(addr).pollFirst();
            } else {
                addresses_map.remove(addr);
            }

            if (!frames.contains(addr) && frames.size() < num_frames) { //Have room in frames, add it
                num_faults++;
                frames.addFirst(addr);
            } else if (!frames.contains(addr) && frames.size() == num_frames) { //No room in frames, evict the page that we will not use for the longest time
                int max_pos = -1;
                String max_pos_addr = null;
                for (String address : frames) { //Find the page that we will not use for the longest time
                    if (addresses_map.containsKey(address)) {
                        if (addresses_map.get(address).getFirst() > max_pos) {
                            max_pos = addresses_map.get(address).getFirst();
                            max_pos_addr = address;
                        }
                    } else {
                        max_pos_addr = address;
                        break;
                    }
                }
                if (dirty_map.containsKey(max_pos_addr)) {
                    num_writes++;
                    dirty_map.remove(max_pos_addr);
                }
                frames.remove(max_pos_addr);
                frames.addFirst(addr);
                num_faults++;
            }
        }
        scanner.close();
        System.out.println("Algorithm: OPT");
        System.out.println("Number of frames: " + num_frames);
        System.out.println("Total memory accesses: " + num_access);
        System.out.println("Total page faults: " + num_faults);
        System.out.println("Total writes to disk: " + num_writes);
    }

    private static HashMap<String, LinkedList<Integer>> createAddressMap(File file) {
        Scanner scanner = null;
        try {
            scanner = new Scanner(file);
        } catch (FileNotFoundException e) {
            System.out.println("ERROR - Could not open file for reading: " + file.getName());
            System.exit(1);
        }

        HashMap<String, LinkedList<Integer>> addresses_map = new HashMap<>();
        int pos = 0;
        while (scanner.hasNextLine()) {
            String[] line_arr = scanner.nextLine().split(" ");
            String addr = line_arr[1].substring(2, 7);
            if (addresses_map.containsKey(addr)) {
                addresses_map.get(addr).add(pos);
            } else {
                LinkedList<Integer> positions = new LinkedList<>();
                positions.add(pos);
                addresses_map.put(addr, positions);
            }
            pos++;
        }
        scanner.close();

        return addresses_map;
    }

    private static void aging(Scanner scanner, int num_frames, int refresh) {
        int num_access = 0;
        int num_faults = 0;
        int num_writes = 0;
        int cycles = 0;

        LinkedList<PTE> frames = new LinkedList<>();

        while (scanner.hasNextLine()) {
            String[] line_arr = scanner.nextLine().split(" ");
            char mode = line_arr[0].charAt(0);
            String addr = line_arr[1].substring(2, 7);
            cycles += Integer.parseInt(line_arr[2]);

            if (cycles >= refresh) {    //update the cycles counters if cycles >= refresh
                int num_shifts = cycles / refresh;
                cycles = cycles % refresh;
                for (PTE pte : frames) {
                    for (int i = 0; i < num_shifts; i++) {
                        pte.setCounter(pte.getReferenced() + pte.getCounter().substring(0, 7));
                        pte.setReferenced(0);
                    }
                }
            }
            cycles++;
            num_access++;

            PTE new_pte = new PTE();
            new_pte.setAddr(addr);
            if (mode == 's') {
                new_pte.setDirty(true);
            }


            if (!containsPTE(frames, new_pte) && frames.size() < num_frames) {
                num_faults++;
                frames.add(new_pte);
            } else if (!containsPTE(frames, new_pte) && frames.size() == num_frames) {
                num_faults++;
                PTE pte_to_evict = frames.getFirst();

                for (PTE frame_pte : frames) {
                    if (Integer.parseInt(frame_pte.getCounter(), 2) < Integer.parseInt(pte_to_evict.getCounter(), 2)) { //Compare the counter first
                        pte_to_evict = frame_pte;
                    } else if (Integer.parseInt(frame_pte.getCounter()) == Integer.parseInt(pte_to_evict.getCounter())) {   //Tie breaking - select nondirty page first, followed by the page with the smaller address
                        if (!frame_pte.isDirty() && pte_to_evict.isDirty()) {
                            pte_to_evict = frame_pte;
                        } else if (frame_pte.isDirty() && pte_to_evict.isDirty()) {
                            if (Integer.parseInt(frame_pte.getAddr(), 16) < Integer.parseInt(pte_to_evict.getAddr(), 16)) {
                                pte_to_evict = frame_pte;
                            }
                        } else if (!frame_pte.isDirty() && !pte_to_evict.isDirty()) {
                            if (Integer.parseInt(frame_pte.getAddr(), 16) < Integer.parseInt(pte_to_evict.getAddr(), 16)) {
                                pte_to_evict = frame_pte;
                            }
                        }
                    }
                }
                if (pte_to_evict.isDirty()) {
                    num_writes++;
                }
                frames.remove(pte_to_evict);
                frames.add(new_pte);
            } else if (containsPTE(frames, new_pte)) {  //Update referenced and dirty with the new_pte
                for (PTE pte : frames) {
                    if (pte.getAddr().equals(new_pte.getAddr())) {
                        pte.setReferenced(1);
                        if (new_pte.isDirty()) {
                            pte.setDirty(true);
                        }
                    }
                }
            }
        }

        System.out.println("Algorithm: AGING");
        System.out.println("Number of frames: " + num_frames);
        System.out.println("Total memory accesses: " + num_access);
        System.out.println("Total page faults: " + num_faults);
        System.out.println("Total writes to disk: " + num_writes);
    }

    private static Boolean containsPTE(LinkedList<PTE> frames, PTE new_pte) {
        for (PTE pte : frames) {
            if (pte.getAddr().equals(new_pte.getAddr()))
                return true;
        }
        return false;
    }
}

class PTE {
    private String addr;
    private String counter;
    private int referenced;
    private boolean dirty;

    public PTE() {
        addr = null;
        counter = "10000000";
        referenced = 0;
        dirty = false;
    }

    public String getAddr() {
        return addr;
    }

    public void setAddr(String addr) {
        this.addr = addr;
    }

    public int getReferenced() {
        return referenced;
    }

    public void setReferenced(int referenced) {
        this.referenced = referenced;
    }

    public String getCounter() {
        return counter;
    }

    public void setCounter(String counter) {
        this.counter = counter;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }
}