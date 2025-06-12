import subprocess
import os
import time
import matplotlib.pyplot as plt
import re
from matplotlib.ticker import LogFormatter


# Define benchmark domains and paths
BENCHMARKS = {
    "blocksworld": r"test/resources/benchmarks/pddl/ipc2000/blocks/strips-typed",
    "depot":       r"test/resources/benchmarks/pddl/ipc2002/depots/strips-automatic",
    "gripper":     r"test/resources/benchmarks/pddl/ipc1998/gripper/adl",
    "logistics":   r"test/resources/benchmarks/pddl/ipc1998/logistics/strips-round2"
    
}

# Planner settings
SAT_PLANNER_DIR = "sat"
HSP_PLANNER_DIR = "hsp"
HSP_PLANNER_JAR = os.path.join(HSP_PLANNER_DIR, "build/libs/pddl4j-4.0.0.jar")
HSP_PLANNER_CMD = ["java", "-cp", HSP_PLANNER_JAR, "fr.uga.pddl4j.planners.statespace.HSP"]

ALL_RESULTS = {}

def run_planner(planner_type, domain_file, problem_file):
    start = time.time()
    try:
        if planner_type == "HSP":
            cmd = HSP_PLANNER_CMD + [domain_file, problem_file]
            cwd = None  # Use project root for HSP

        elif planner_type == "SAT":
            mvn_executable = "mvn.cmd" if os.name == "nt" else "mvn"
            relative_domain_path = os.path.relpath(domain_file, SAT_PLANNER_DIR)
            relative_problem_path = os.path.relpath(problem_file, SAT_PLANNER_DIR)
            cmd = [
                mvn_executable,
                "exec:java",
                "-Dexec.mainClass=fr.uga.pddl4j.examples.satplanner.SATP",  
                f"-Dexec.args={relative_domain_path} {relative_problem_path}"
            ]
            cwd = SAT_PLANNER_DIR

        else:
            raise ValueError("Invalid planner type")
        
        print(f"\n[Running {planner_type}]")
        result = subprocess.run(
            cmd,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=600
        )

        output = result.stdout.decode()
        runtime = time.time() - start
        makespan = extract_makespan(output)
        print(f"runtime {runtime}")
        print(f"makespan {makespan}")
        return runtime, makespan

    except subprocess.TimeoutExpired:
        print(f"Timeout: {planner_type} on {problem_file}")
        return None, None
    except Exception as e:
        print(f"Error running {planner_type} on {problem_file}: {e}")
        return None, None

def extract_makespan(output):
    max_step = -1
    step_pattern = re.compile(r'^(\d+):')

    for line in output.splitlines():
        match = step_pattern.match(line.strip())
        if match:
            step_num = int(match.group(1))
            if step_num > max_step:
                max_step = step_num

    return max_step + 1 if max_step >= 0 else None

def compare_domain(domain_name, domain_path):
    print(f"\nComparing planners on: {domain_name}")
    domain_file = os.path.join(domain_path, "domain.pddl")
    domain_limits = {
        "blocksworld": 10,
        "gripper": 4,
        "depot": 2,
        "logistics": 2
    }
    limit = domain_limits.get(domain_name, 5)  # default to 5 if domain not in dict
    problem_files = sorted(f for f in os.listdir(domain_path) if f.startswith("p"))[:limit]
    results = {"HSP": [], "SAT": []}

    for problem_file in problem_files:
        problem_path = os.path.join(domain_path, problem_file)
        print(f"   Problem: {problem_file}")

        hsp_runtime, hsp_makespan = run_planner("HSP", domain_file, problem_path)
        sat_runtime, sat_makespan = run_planner("SAT", domain_file, problem_path)

        results["HSP"].append((problem_file, hsp_runtime, hsp_makespan))
        results["SAT"].append((problem_file, sat_runtime, sat_makespan))

    ALL_RESULTS[domain_name] = results

def plot_results():
    os.makedirs("figures", exist_ok=True)

    for domain_name, results in ALL_RESULTS.items():
        sorted_hsp = sorted(results["HSP"], key=lambda x: (x[1] if x[1] is not None else float("inf")))
        problem_names = [x[0] for x in sorted_hsp]
        sat_dict = {x[0]: x for x in results["SAT"]}

        hsp_runtimes = [x[1] for x in sorted_hsp]
        sat_runtimes = [sat_dict[name][1] if name in sat_dict else None for name in problem_names]

        hsp_makespans = [x[2] for x in sorted_hsp]
        sat_makespans = [sat_dict[name][2] if name in sat_dict else None for name in problem_names]

        # Runtime plot
        plt.figure(figsize=(10, 8))  # Taille de la figure
        plt.suptitle(f"{domain_name}", fontsize=16)  # Titre global

        plt.plot(problem_names, hsp_runtimes, marker="o", label="HSP")
        plt.plot(problem_names, sat_runtimes, marker="x", label="SAT Planner")
        plt.title(f"Runtime - {domain_name}")
        plt.xlabel("Problem (runtime comparison)")
        plt.ylabel("Time (s)")
  #      plt.yscale("log")        
        plt.legend()
        plt.xticks(rotation=45)
      #  plt.tight_layout()
        plt.savefig(f"figures/{domain_name}_runtime.png")
        plt.close()

        # Makespan plot
        plt.subplot(2, 1, 2)        
        plt.plot(problem_names, hsp_makespans, marker="o", label="HSP")
        plt.plot(problem_names, sat_makespans, marker="x", label="SAT Planner")
        plt.title(f"Makespan - {domain_name}")
        plt.xlabel("Problem (makespan comparison)")
        plt.ylabel("Plan Length")
        plt.legend()
        plt.xticks(rotation=45)
        plt.tight_layout(rect=[0, 0, 1, 0.96])
        plt.savefig(f"figures/{domain_name}_makespan.png")
        plt.close()

if __name__ == "__main__":
    for name, path in BENCHMARKS.items():
        compare_domain(name, os.path.normpath(path))
    plot_results()
    print("\nAll comparisons done. Figures saved in 'figures/' folder.")
